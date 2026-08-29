package com.turbofeed.gateway.service.validation;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 上传校验责任链装配与入口。
 *
 * <p>Spring 注入 {@code List<UploadValidator>} 时自动按 {@code @Order} 排序，
 * 构造期一次性建链（环环 setNext）；运行期零锁零重建，链结构不可变。</p>
 *
 * <p>扩展方式：新增校验规则 = 新建一个 {@code UploadValidator} 实现类 + {@code @Component}
 * + {@code @Order(n)}，链与 {@code MediaUploadService} 零改动（对扩展开放）。</p>
 *
 * <p>当前链序：</p>
 * <ol>
 *   <li>{@code ConcurrentUploadValidator}（Order 1）——Redis 并发占位，先占坑防校验期间并发穿透；</li>
 *   <li>{@code FileConstraintValidator}（Order 2）——批量数 / 空文件 / 大小 / Magic Number。</li>
 * </ol>
 */
@Component
public class UploadValidationChain {

    private final List<UploadValidator> validators;

    public UploadValidationChain(List<UploadValidator> validators) {
        this.validators = List.copyOf(validators);
        for (int i = 0; i < this.validators.size() - 1; i++) {
            this.validators.get(i).setNext(this.validators.get(i + 1));
        }
    }

    /**
     * 执行全链校验。
     *
     * <p>构造上下文 → 首环开始逐环推进；任一环节抛 {@code BizException} 即中断。
     * 返回的上下文携带沿链累积的校验结果（嗅探格式）与完成回调，调用方须在
     * try/finally 中执行 {@code context.runCompletionCallbacks()}。</p>
     *
     * @param userId 归属用户（线程上下文取出的用户标识）
     * @param files  multipart 上传文件数组（合法性由链内裁定）
     * @return 校验通过的上下文（格式已写回、回调已注册）
     */
    public UploadValidation validate(String userId, MultipartFile[] files) {
        UploadValidation context = new UploadValidation(userId, files);
        if (!validators.isEmpty()) {
            validators.get(0).validate(context);
        }
        return context;
    }
}
