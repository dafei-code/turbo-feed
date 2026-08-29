package com.turbofeed.gateway.service.validation;

/**
 * 上传校验责任链处理器基类（模板方法模式）。
 *
 * <p><b>子类只实现 {@link #doValidate}，链推进由基类统一负责</b>——{@link #validate}
 * 是 final 模板方法：先执行本校验，再推进 next。子类无法忘记调 next（手工责任链
 * 最常见的断链事故），也不允许跳过后续环节。</p>
 *
 * <p>校验失败的表达方式：抛 {@code BizException}(UPLOAD_INVALID / UPLOAD_IN_PROGRESS 等)，
 * 中断链并冒泡到全局异常处理器，不做"校验结果布尔值回传"（失败即异常，主链路零分支）。</p>
 *
 * <p>顺序契约：实现类标注 Spring {@code @Order}，注入 {@link UploadValidationChain}
 * 时按 Order 排序建链。先廉价本地校验、后涉及外部依赖的校验是通用原则——本项目
 * 并发占位在前（先占坑防并发穿透），文件约束在后。</p>
 */
public abstract class UploadValidator {

    /** 链上的下一个处理器（由 {@link UploadValidationChain} 建链时装配）。 */
    protected UploadValidator next;

    void setNext(UploadValidator next) {
        this.next = next;
    }

    /**
     * 模板方法：本校验 → 推进 next。final 防子类覆盖破坏链推进。
     */
    public final void validate(UploadValidation context) {
        doValidate(context);
        if (next != null) {
            next.validate(context);
        }
    }

    /** 本环节校验逻辑：失败抛 BizException，通过即静默返回。 */
    protected abstract void doValidate(UploadValidation context);
}
