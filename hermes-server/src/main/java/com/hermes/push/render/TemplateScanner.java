package com.hermes.push.render;

import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 模板静态扫描器：在渲染前/保存时扫描模板内容，拦截危险指令。
 *
 * <p><b>定位：绊线（tripwire），非强制防线。</b>
 * 扫描层基于字面量匹配，可被字符串拼接绕过（如 {@code "?n" + "ew()}）。
 * 强制防线是 {@link SandboxFreemarker} 的求值期沙箱
 * （ALLOWS_NOTHING_RESOLVER + apiBuiltinEnabled=false），
 * 在模板求值时生效，拼接绕不过。两层缺一不可。
 *
 * <p>扫描关键字（精确大小写，Freemarker 内建区分大小写）：
 * <ul>
 *   <li>?new：调用任意 Java 构造器（RCE 风险）</li>
 *   <li>?api：访问对象的 Java API（反射/RCE 风险）</li>
 *   <li>?eval：动态求值字符串为模板表达式（可绕过静态扫描）</li>
 *   <li>?interpret：动态求值字符串为模板片段（可绕过静态扫描）</li>
 *   <li>freemarker.：freemarker 包前缀，覆盖 freemarker.template.utility/ObjectConstructor/Execute/JythonRuntime 等全部变体</li>
 * </ul>
 */
@Component
public class TemplateScanner {

  // 注意：更具体的模式列在前面，确保命中时返回最精确的命中词
  private static final String[] DANGEROUS_PATTERNS = {
      "?new",
      "?api",
      "?eval",
      "?interpret",
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
