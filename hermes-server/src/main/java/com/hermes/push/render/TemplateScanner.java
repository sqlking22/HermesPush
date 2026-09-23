package com.hermes.push.render;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 模板静态扫描器：在渲染前/保存时扫描模板内容，拦截危险指令。
 *
 * 扫描关键字（精确大小写，Freemarker 内建区分大小写）：
 * - ?new：调用任意 Java 构造器（RCE 风险）
 * - ?api：访问对象的 Java API（反射/RCE 风险）
 * - freemarker.：freemarker 包前缀，覆盖 freemarker.template.utility/ObjectConstructor/Execute/JythonRuntime 等全部变体
 */
@Component
public class TemplateScanner {

  // 注意：更具体的模式列在前面，确保命中时返回最精确的命中词
  private static final String[] DANGEROUS_PATTERNS = {
      "?new",
      "?api",
      "ObjectConstructor",
      "Execute",
      "JythonRuntime",
      "freemarker.template.utility",
      "freemarker."
  };

  public void scan(String content) {
    if (content == null) return;
    for (String pattern : DANGEROUS_PATTERNS) {
      if (content.contains(pattern)) {
        throw new BizException(ErrorCode.TPL_010, "命中危险指令: " + pattern);
      }
    }
  }
}
