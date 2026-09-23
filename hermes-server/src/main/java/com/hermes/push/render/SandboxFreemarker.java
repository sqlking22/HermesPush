package com.hermes.push.render;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import freemarker.core.InvalidReferenceException;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 沙箱化 Freemarker 渲染器。
 *
 * 沙箱三件套（缺一即评审 Critical）：
 * 1. cfg.setNewBuiltinClassResolver(ALLOWS_NOTHING_RESOLVER) — 禁止 ?new 构造任意 Java 对象
 * 2. cfg.setAPIBuiltinEnabled(false) — 禁止 ?api 访问对象 Java API
 * 3. TemplateExceptionHandler 快速失败（rethrow 包装）+ setLogTemplateExceptions(false)
 *
 * 超时机制：共享虚拟线程 executor，future.get(timeoutSec, SECONDS)，超时 cancel(true) + 抛 SYS_005。
 * 残留风险：Freemarker 无限循环不响应中断时渲染线程可能继续消耗 CPU，M3 渲染进程隔离根治。
 */
@Component
public class SandboxFreemarker {

  private final Configuration cfg;
  private final ExecutorService renderExecutor;

  public SandboxFreemarker() {
    cfg = new Configuration(Configuration.VERSION_2_3_33);
    cfg.setDefaultEncoding("UTF-8");
    // 沙箱三件套 1：禁止 ?new 构造任意类
    cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
    // 沙箱三件套 2：禁止 ?api 内建
    cfg.setAPIBuiltinEnabled(false);
    // 沙箱三件套 3：模板异常快速失败（rethrow 包装），不记录到日志
    cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    cfg.setLogTemplateExceptions(false);

    // 共享的虚拟线程 executor，随 bean 生命周期
    this.renderExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public String render(String name, String content, Map<String, Object> model, int timeoutSec) {
    Template t;
    try {
      t = new Template(name, new StringReader(content), cfg);
    } catch (Exception e) {
      String firstLine = e.getMessage() == null ? ""
          : e.getMessage().lines().findFirst().orElse("");
      throw new BizException(ErrorCode.TPL_003, "模板解析失败: " + firstLine);
    }

    StringWriter out = new StringWriter();
    Future<?> f = null;
    try {
      f = renderExecutor.submit(() -> {
        try {
          t.process(model, out);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      f.get(timeoutSec, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      // 注意：Freemarker 无限循环不响应中断时，渲染线程可能继续消耗 CPU
      // 残留风险，M3 渲染进程隔离根治
      if (f != null) {
        try {
          f.cancel(true);
        } catch (Exception ignored) {
          // 最佳努力取消
        }
      }
      throw new BizException(ErrorCode.SYS_005, "模板渲染超时(" + timeoutSec + "s)");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      // 解包 RuntimeException 包装层
      if (cause instanceof RuntimeException re && re.getCause() != null) {
        cause = re.getCause();
      }
      if (cause instanceof InvalidReferenceException ire) {
        throw new BizException(ErrorCode.TPL_003,
            "引用不存在的字段: " + ire.getBlamedExpressionString()
                + " (line " + ire.getLineNumber() + ")");
      }
      throw new BizException(ErrorCode.TPL_003,
          cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(cause));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException(ErrorCode.SYS_005, "渲染被中断");
    }
    return out.toString();
  }
}
