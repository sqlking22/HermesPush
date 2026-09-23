package com.hermes.push.dataset;

import com.hermes.push.common.ApiResponse;
import com.hermes.push.common.CurrentUserHolder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class PreviewController {
  private final PreviewService previewService;

  @PostMapping("/{id}/preview")
  public ApiResponse<PreviewVO> preview(@PathVariable Long id,
                                        @RequestBody PreviewRequest req,
                                        HttpServletRequest request) {
    String operator = CurrentUserHolder.get();
    String ip = request.getRemoteAddr();
    return ApiResponse.ok(previewService.preview(id, req, operator, ip));
  }
}
