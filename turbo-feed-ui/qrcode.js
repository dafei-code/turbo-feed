/*
 * qrcode.js — 零依赖二维码生成器（Byte 模式 / 纠错 L / 自动版本 1~10）。
 *
 * 用途：短链分享卡片页展示可扫码的短链二维码。只编码 URL（ASCII），
 * 故用 Byte 模式 + 纠错级别 L（7% 恢复能力）即可，版本上限 10 可容纳
 * 271 字节（L）——足够任何合理长度的短链地址。
 *
 * 算法实现参考 Nayuki QR Code generator 的公开算法思路（MIT），
 * 这里是面向本项目的精简独立实现：GF(256) Reed-Solomon、功能图形、
 * 数据掩码（8 种自动选优）、格式/版本信息。
 *
 * 用法：
 *   var svg = QrCode.svg('https://example.com/s/abcdefghijk', 4); // 返回 SVG 字符串
 */
(function (global) {
  'use strict';

  // ---- GF(256) with primitive polynomial 0x11D ----
  function gfMul(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11D);
      z ^= ((y >>> i) & 1) * x;
    }
    return z & 0xFF;
  }

  function reedSolomonComputeDivisor(degree) {
    var result = [];
    for (var i = 0; i < degree - 1; i++) result.push(0);
    result.push(1);
    var root = 1;
    for (var j = 0; j < degree; j++) {
      for (var k = 0; k < result.length; k++) {
        result[k] = gfMul(result[k], root);
        if (k + 1 < result.length) result[k] ^= result[k + 1];
      }
      root = gfMul(root, 2);
    }
    return result;
  }

  function reedSolomonComputeRemainder(data, divisor) {
    var result = divisor.map(function () { return 0; });
    data.forEach(function (b) {
      var factor = b ^ result.shift();
      result.push(0);
      divisor.forEach(function (d, i) { result[i] ^= gfMul(d, factor); });
    });
    return result;
  }

  // ---- Version capacity table (total codewords, ec codewords per block, num blocks) ----
  // 仅 geometry + L/M 的 capacity；索引 = version - 1。0 = 不支持该模式。
  // 数据来自 ISO/IEC 18004 Table 7/9（L 与 M 各一组）。
  var ECC_L = [
    [19, 7, 1], [34, 10, 1], [55, 15, 1], [80, 20, 1], [108, 26, 1],
    [136, 18, 2], [156, 20, 2], [194, 24, 2], [232, 30, 2], [274, 18, 4]
  ];
  var ECC_M = [
    [16, 10, 1], [28, 16, 1], [44, 26, 1], [64, 18, 2], [86, 24, 2],
    [108, 16, 4], [124, 18, 4], [154, 22, 4], [182, 22, 5], [216, 26, 5]
  ];
  // 每个版本的 alignment pattern 中心坐标（version 2+）。查表比动态算简单可靠。
  var ALIGN = [
    [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38],
    [6, 24, 42], [6, 26, 46], [6, 28, 50]
  ];

  function chooseVersion(dataBytes, ecc) {
    var table = ecc === 'M' ? ECC_M : ECC_L;
    // Byte mode 头：4 bit mode + 8/16 bit length；L/M 用 8 bit（version 1-9），version 10 用 16 bit
    for (var v = 1; v <= 10; v++) {
      var entry = table[v - 1];
      var totalDataCodewords = entry[0] - entry[1] * entry[2]; // total - ec
      var lengthBits = v <= 9 ? 8 : 16;
      var neededBits = 4 + lengthBits + dataBytes.length * 8;
      var neededBytes = Math.ceil(neededBits / 8);
      if (neededBytes <= totalDataCodewords) return v;
    }
    throw new Error('内容过长，超出二维码支持范围');
  }

  // ---- Bit buffer ----
  function BitBuffer() { this.bits = []; }
  BitBuffer.prototype.append = function (val, len) {
    for (var i = len - 1; i >= 0; i--) this.bits.push((val >>> i) & 1);
  };

  function makeMatrix(version, dataBytes, ecc) {
    var size = version * 4 + 17;
    var modules = [], isFunc = [];
    for (var r = 0; r < size; r++) {
      modules.push(new Array(size).fill(false));
      isFunc.push(new Array(size).fill(false));
    }
    function setFunc(r, c, dark) { modules[r][c] = dark; isFunc[r][c] = true; }

    // timing patterns
    for (var i = 0; i < size; i++) {
      setFunc(6, i, i % 2 === 0);
      setFunc(i, 6, i % 2 === 0);
    }
    // finder patterns + separators
    function drawFinder(r, c) {
      for (var dr = -4; dr <= 4; dr++) {
        for (var dc = -4; dc <= 4; dc++) {
          var rr = r + dr, cc = c + dc;
          if (rr < 0 || rr >= size || cc < 0 || cc >= size) continue;
          var dist = Math.max(Math.abs(dr), Math.abs(dc));
          setFunc(rr, cc, dist !== 2 && dist !== 4);
        }
      }
    }
    drawFinder(3, 3);
    drawFinder(3, size - 4);
    drawFinder(size - 4, 3);
    // alignment patterns
    var align = ALIGN[version - 1];
    for (var ai = 0; ai < align.length; ai++) {
      for (var aj = 0; aj < align.length; aj++) {
        var ar = align[ai], ac = align[aj];
        // 跳过与 finder 重叠的三个角
        if ((ai === 0 && aj === 0) || (ai === 0 && aj === align.length - 1) ||
            (ai === align.length - 1 && aj === 0)) continue;
        for (var dr2 = -2; dr2 <= 2; dr2++) {
          for (var dc2 = -2; dc2 <= 2; dc2++) {
            setFunc(ar + dr2, ac + dc2, Math.max(Math.abs(dr2), Math.abs(dc2)) !== 1);
          }
        }
      }
    }
    // dark module
    setFunc(size - 8, 8, true);
    // format info area（先占位，最后填）
    for (var k = 0; k <= 8; k++) {
      if (!isFunc[8][k]) setFunc(8, k, false);
      if (!isFunc[k][8]) setFunc(k, 8, false);
      if (!isFunc[size - 1 - k][8]) setFunc(size - 1 - k, 8, false);
      if (!isFunc[8][size - 1 - k]) setFunc(8, size - 1 - k, false);
    }
    // version info area（version >= 7）
    if (version >= 7) {
      var rem = version;
      for (var p = 0; p < 18; p++) {
        var bit = (rem >>> p) & 1;
        var a = size - 11 + p % 3, b = Math.floor(p / 3);
        setFunc(a, b, !!bit);
        setFunc(b, a, !!bit);
      }
    }

    // ---- build codewords ----
    var table = ecc === 'M' ? ECC_M : ECC_L;
    var entry = table[version - 1];
    var totalCodewords = entry[0], ecLen = entry[1], numBlocks = entry[2];
    var dataCodewords = totalCodewords - ecLen * numBlocks;
    var bb = new BitBuffer();
    bb.append(4, 4); // Byte mode
    bb.append(dataBytes.length, version <= 9 ? 8 : 16);
    dataBytes.forEach(function (b) { bb.append(b, 8); });
    // terminator
    var capacityBits = dataCodewords * 8;
    var terminatorLen = Math.min(4, capacityBits - bb.bits.length);
    for (var t = 0; t < terminatorLen; t++) bb.bits.push(0);
    // pad to byte boundary
    while (bb.bits.length % 8 !== 0) bb.bits.push(0);
    // alternate pad bytes
    var pad = 0xEC;
    while (bb.bits.length < capacityBits) {
      bb.append(pad, 8);
      pad ^= 0xFD; // 0xEC ^ 0x11 = 0xFD ... alternate 0xEC / 0x11
    }
    var allCodewords = [];
    for (var q = 0; q < bb.bits.length; q += 8) {
      var byte = 0;
      for (var b2 = 0; b2 < 8; b2++) byte = (byte << 1) | bb.bits[q + b2];
      allCodewords.push(byte);
    }

    // split into blocks + compute EC
    var blocks = [], ecBlocks = [];
    var divisor = reedSolomonComputeDivisor(ecLen);
    var idx = 0;
    var shortBlockLen = Math.floor(dataCodewords / numBlocks);
    var numLong = dataCodewords % numBlocks;
    for (var blk = 0; blk < numBlocks; blk++) {
      var len = shortBlockLen + (blk < numBlocks - numLong ? 0 : 1);
      var dataBlock = allCodewords.slice(idx, idx + len);
      idx += len;
      blocks.push(dataBlock);
      ecBlocks.push(reedSolomonComputeRemainder(dataBlock, divisor));
    }
    // interleave data then ec
    var finalWords = [];
    var maxDataLen = Math.max.apply(null, blocks.map(function (x) { return x.length; }));
    for (var col = 0; col < maxDataLen; col++) {
      blocks.forEach(function (b) { if (col < b.length) finalWords.push(b[col]); });
    }
    for (var col2 = 0; col2 < ecLen; col2++) {
      ecBlocks.forEach(function (e) { finalWords.push(e[col2]); });
    }

    // ---- draw codewords with zigzag, skipping function modules ----
    var bitIdx = 0;
    function nextBit() {
      if (bitIdx >= finalWords.length * 8) return 0;
      var word = finalWords[Math.floor(bitIdx / 8)];
      var bit = (word >>> (7 - (bitIdx % 8))) & 1;
      bitIdx++;
      return bit;
    }
    var upward = true;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) {
        var row = upward ? size - 1 - vert : vert;
        for (var j2 = 0; j2 < 2; j2++) {
          var col2b = right - j2;
          if (!isFunc[row][col2b]) modules[row][col2b] = nextBit() === 1;
        }
      }
      upward = !upward;
    }

    // ---- choose best mask ----
    function maskFn(mask, r, c) {
      switch (mask) {
        case 0: return (r + c) % 2 === 0;
        case 1: return r % 2 === 0;
        case 2: return c % 3 === 0;
        case 3: return (r + c) % 3 === 0;
        case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
        case 5: return (r * c) % 2 + (r * c) % 3 === 0;
        case 6: return ((r * c) % 2 + (r * c) % 3) % 2 === 0;
        default: return ((r + c) % 2 + (r * c) % 3) % 2 === 0;
      }
    }
    function applyMask(mask) {
      for (var r = 0; r < size; r++) {
        for (var c = 0; c < size; c++) {
          if (!isFunc[r][c] && maskFn(mask, r, c)) modules[r][c] = !modules[r][c];
        }
      }
    }
    function penalty() {
      var p = 0;
      // adjacent modules + 2x2 blocks
      for (var r = 0; r < size; r++) {
        for (var c = 0; c < size; c++) {
          var dark = modules[r][c];
          if (c + 6 < size && dark === modules[r][c + 1] && dark === modules[r][c + 2] &&
              dark === modules[r][c + 3] && dark === modules[r][c + 4] && dark === modules[r][c + 5] && dark === modules[r][c + 6]) p += 4;
          if (r + 6 < size && dark === modules[r + 1][c] && dark === modules[r + 2][c] &&
              dark === modules[r + 3][c] && dark === modules[r + 4][c] && dark === modules[r + 5][c] && dark === modules[r + 6][c]) p += 4;
          if (r + 1 < size && c + 1 < size) {
            var v = 0;
            for (var dr = 0; dr < 2; dr++) for (var dc = 0; dc < 2; dc++) if (modules[r + dr][c + dc]) v++;
            if (v === 0 || v === 4) p += 3;
          }
        }
      }
      return p;
    }
    var bestMask = 0, bestPenalty = Infinity;
    for (var m = 0; m < 8; m++) {
      applyMask(m);
      var pen = penalty();
      if (pen < bestPenalty) { bestPenalty = pen; bestMask = m; }
      applyMask(m); // undo
    }
    applyMask(bestMask);

    // format info: (ec bits << 3) | mask, BCH(15,5) with generator 0x537, xor 0x5412
    var ecBits = ecc === 'M' ? 0x00 : 0x01;
    var data = (ecBits << 3) | bestMask;
    var rem = data;
    for (var i2 = 0; i2 < 10; i2++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var formatBits = ((data << 10) | rem) ^ 0x5412;
    function formatBit(i) { return ((formatBits >>> i) & 1) !== 0; }
    // 第一份格式信息（setFunc 参数是 (row, col)）
    for (var f = 0; f <= 5; f++) setFunc(f, 8, formatBit(f));
    setFunc(7, 8, formatBit(6));
    setFunc(8, 8, formatBit(7));
    setFunc(8, 7, formatBit(8));
    for (var f2 = 9; f2 <= 14; f2++) setFunc(8, 14 - f2, formatBit(f2));
    // 第二份格式信息
    for (var f3 = 0; f3 <= 7; f3++) setFunc(8, size - 1 - f3, formatBit(f3));
    for (var f4 = 8; f4 <= 14; f4++) setFunc(size - 15 + f4, 8, formatBit(f4));
    setFunc(size - 8, 8, true); // dark module (fixed)

    return modules;
  }

  function svg(text, scale) {
    var bytes = [];
    for (var i = 0; i < text.length; i++) {
      var code = text.charCodeAt(i);
      if (code < 128) bytes.push(code);
      else {
        var encoded = unescape(encodeURIComponent(text.charAt(i)));
        for (var j = 0; j < encoded.length; j++) bytes.push(encoded.charCodeAt(j));
      }
    }
    var version = chooseVersion(bytes, 'L');
    var modules = makeMatrix(version, bytes, 'L');
    var size = modules.length;
    var quiet = 4;
    var dim = (size + quiet * 2) * (scale || 4);
    var parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="' + dim + '" height="' + dim +
      '" viewBox="0 0 ' + (size + quiet * 2) + ' ' + (size + quiet * 2) + '" shape-rendering="crispEdges">'];
    parts.push('<rect width="100%" height="100%" fill="#ffffff"/>');
    for (var r = 0; r < size; r++) {
      for (var c = 0; c < size; c++) {
        if (modules[r][c]) {
          parts.push('<rect x="' + (c + quiet) + '" y="' + (r + quiet) + '" width="1" height="1" fill="#000000"/>');
        }
      }
    }
    parts.push('</svg>');
    return parts.join('');
  }

  global.QrCode = { svg: svg };
})(window);
