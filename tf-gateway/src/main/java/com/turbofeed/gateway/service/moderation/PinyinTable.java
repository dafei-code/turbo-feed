package com.turbofeed.gateway.service.moderation;

import java.util.HashMap;
import java.util.Map;

/**
 * 高频涉敏汉字 → 拼音映射表（P1：拼音绕过防护）。
 *
 * <p><b>为什么不用完整拼音库</b>：完整方案需要 2 万字表（≈200KB），引入第三方依赖
 * （pinyin4j / TinyPinyin），与本项目「零三方依赖」定位冲突。而敏感词场景只需要覆盖
 * <b>可能出现在敏感词中的汉字</b>——涉政、涉黄、涉暴、涉毒、涉赌、涉骗的高频字，
 * 约 200 字即可覆盖绝大多数拼音绕过变体。</p>
 *
 * <p><b>误杀控制</b>：拼音折叠天然有同音歧义（"shi huang" 可以是"涉黄"也可以是"时光"）。
 * 本表刻意<b>只收录涉敏高频字</b>而非全量汉字——日常文本里的"时""光"等不在表中，
 * 它们的拼音不会进入归一化文本，因此"时光"不会被误拼成敏感词的拼音。
 * 这是「收录范围」而非「算法精度」在控制误杀面。</p>
 *
 * <p><b>多音字处理</b>：取<b>最常见读音</b>（如「重」取 zhong 而非 chong），
 * 不做多音全展开——多音全展开会让归一化文本膨胀数倍，且 AC 匹配的词条是运营录入的
 * 拼音变体，只要其中一种读音命中即可。未收录的多音字原样保留。</p>
 *
 * <p><b>维护纪律</b>：新增敏感词时，检查其汉字是否已在表中；若否且该字不常见，
 * 把它加入本表。<b>不要把日常用字加入</b>——那会放大误杀面。</p>
 */
public final class PinyinTable {

    /**
     * 高频涉敏汉字 → 拼音（无声调，全小写）。
     *
     * <p>覆盖范围：涉政 / 涉黄 / 涉暴 / 涉毒 / 涉赌 / 涉骗 / 涉恐 的高频字。
     * 声调不编码——拼音绕过者不会带声调输入，编码声调只会让匹配变复杂而不增加覆盖。</p>
     */
    private static final Map<Character, String> HAN_TO_PINYIN = buildTable();

    private static Map<Character, String> buildTable() {
        Map<Character, String> m = new HashMap<>(256);
        // 涉政
        put(m, '毛', "mao"); put(m, '泽', "ze"); put(m, '东', "dong");
        put(m, '主', "zhu"); put(m, '席', "xi");
        put(m, '邓', "deng"); put(m, '小', "xiao"); put(m, '平', "ping");
        put(m, '江', "jiang"); put(m, '胡', "hu");
        put(m, '习', "xi"); put(m, '近', "jin"); put(m, '李', "li"); put(m, '克', "ke");
        put(m, '强', "qiang"); put(m, '总', "zong"); put(m, '理', "li");
        put(m, '共', "gong"); put(m, '产', "chan"); put(m, '党', "dang");
        put(m, '政', "zheng"); put(m, '府', "fu"); put(m, '军', "jun");
        put(m, '国', "guo"); put(m, '家', "jia"); put(m, '领', "ling"); put(m, '导', "dao");
        put(m, '中', "zhong"); put(m, '央', "yang");
        // 涉黄
        put(m, '黄', "huang"); put(m, '色', "se"); put(m, '情', "qing");
        put(m, '淫', "yin"); put(m, '秽', "hui"); put(m, '娼', "chang");
        put(m, '妓', "ji"); put(m, '嫖', "piao");
        put(m, '裸', "luo"); put(m, '乳', "ru"); put(m, '阴', "yin");
        put(m, '胸', "xiong"); put(m, '肛', "gang"); put(m, '性', "xing");
        put(m, '交', "jiao"); put(m, '奸', "jian"); put(m, '卖', "mai");
        put(m, '乱', "luan"); put(m, '荡', "dang");
        put(m, '骚', "sao"); put(m, '媚', "mei"); put(m, '欲', "yu");
        // 涉暴
        put(m, '杀', "sha"); put(m, '枪', "qiang"); put(m, '弹', "dan");
        put(m, '炸', "zha"); put(m, '爆', "bao"); put(m, '刀', "dao");
        put(m, '砍', "kan"); put(m, '刺', "ci"); put(m, '尸', "shi");
        put(m, '血', "xue"); put(m, '伤', "shang"); put(m, '害', "hai");
        put(m, '击', "ji"); put(m, '暴', "bao"); put(m, '徒', "tu");
        put(m, '恐', "kong"); put(m, '袭', "xi"); put(m, '极', "ji");
        put(m, '端', "duan"); put(m, '战', "zhan"); put(m, '斗', "dou");
        // 涉毒
        put(m, '毒', "du"); put(m, '贩', "fan"); put(m, '吸', "xi");
        put(m, '品', "pin"); put(m, '大', "da");
        put(m, '麻', "ma"); put(m, '冰', "bing"); put(m, '瘾', "yin");
        put(m, '鸦', "ya"); put(m, '片', "pian"); put(m, '海', "hai");
        put(m, '洛', "luo"); put(m, '因', "yin");
        put(m, '摇', "yao"); put(m, '头', "tou"); put(m, '丸', "wan");
        put(m, '粉', "fen");
        // 涉赌
        put(m, '赌', "du"); put(m, '博', "bo"); put(m, '投', "tou");
        put(m, '注', "zhu"); put(m, '彩', "cai"); put(m, '票', "piao");
        put(m, '场', "chang"); put(m, '轮', "lun");
        put(m, '盘', "pan"); put(m, '牌', "pai"); put(m, '局', "ju");
        // 涉骗
        put(m, '诈', "zha"); put(m, '骗', "pian"); put(m, '欺', "qi");
        put(m, '坑', "keng"); put(m, '蒙', "meng");
        put(m, '拐', "guai"); put(m, '洗', "xi");
        put(m, '钱', "qian"); put(m, '套', "tao"); put(m, '路', "lu");
        // 涉恐
        put(m, '怖', "bu"); put(m, '组', "zu");
        put(m, '织', "zhi");
        put(m, '分', "fen"); put(m, '裂', "lie"); put(m, '独', "du");
        put(m, '立', "li"); put(m, '反', "fan"); put(m, '动', "dong");
        // 通用敏感动词/名词
        put(m, '禁', "jin"); put(m, '封', "feng"); put(m, '查', "cha");
        put(m, '删', "shan"); put(m, '除', "chu"); put(m, '违', "wei");
        put(m, '规', "gui"); put(m, '法', "fa"); put(m, '罪', "zui");
        put(m, '犯', "fan"); put(m, '刑', "xing"); put(m, '拘', "ju");
        put(m, '捕', "bu"); put(m, '判', "pan");
        // 词族公共前缀（漏掉会让整族拼音绕过失效，如「涉黄/涉政/涉毒/涉赌」）
        put(m, '涉', "she"); put(m, '造', "zao"); put(m, '谣', "yao");
        put(m, '传', "chuan"); put(m, '播', "bo"); put(m, '散', "san");
        put(m, '恐', "kong"); put(m, '吓', "he"); put(m, '威', "wei");
        put(m, '胁', "xie"); put(m, '勒', "le"); put(m, '索', "suo");
        return Map.copyOf(m);
    }

    private static void put(Map<Character, String> m, char c, String pinyin) {
        m.put(c, pinyin);
    }

    /** 查汉字的拼音；未收录返回 {@code null}。 */
    public static String of(char c) {
        return HAN_TO_PINYIN.get(c);
    }

    /** 映射表收录的汉字数。 */
    public static int size() {
        return HAN_TO_PINYIN.size();
    }

    private PinyinTable() {
    }
}
