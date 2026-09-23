package com.hermes.push.render;

import com.hermes.push.common.BizException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TemplateScannerTest {
  TemplateScanner scanner = new TemplateScanner();

  @Test void normalTemplatePasses() {
    scanner.scan("正常 ${x} 模板");
  }

  @Test void newBuiltinIsBlocked() {
    assertThatThrownBy(() -> scanner.scan("<#assign ex='freemarker.template.utility.Execute'?new()>${ex('whoami')}"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-010")
        .hasMessageContaining("命中危险指令")
        .hasMessageContaining("?new");
  }

  @Test void apiBuiltinIsBlocked() {
    assertThatThrownBy(() -> scanner.scan("${'x'?api.class.forName('java.lang.Runtime')}"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-010")
        .hasMessageContaining("命中危险指令")
        .hasMessageContaining("?api");
  }

  @Test void executeIsBlocked() {
    assertThatThrownBy(() -> scanner.scan("freemarker.template.utility.Execute"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("TPL-010")
        .hasMessageContaining("命中危险指令")
        .hasMessageContaining("Execute");
  }
}
