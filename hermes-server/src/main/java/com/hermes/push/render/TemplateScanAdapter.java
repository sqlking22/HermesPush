package com.hermes.push.render;

import com.hermes.push.task.TemplateScanPort;
import org.springframework.stereotype.Component;

/**
 * 将 TemplateScanner 适配为 Task 包的 TemplateScanPort 接口。
 * 注册后 Task 12 的 saveVersion/create 校验链路自动获得模板扫描。
 */
@Component
public class TemplateScanAdapter implements TemplateScanPort {

  private final TemplateScanner scanner;

  public TemplateScanAdapter(TemplateScanner scanner) {
    this.scanner = scanner;
  }

  @Override
  public void scan(String content) {
    scanner.scan(content);
  }
}
