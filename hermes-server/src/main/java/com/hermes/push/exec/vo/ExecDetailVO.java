package com.hermes.push.exec.vo;

import com.hermes.push.exec.StageCosts;
import com.hermes.push.exec.TaskExec;
import java.util.List;

public record ExecDetailVO(
    TaskExec exec,
    List<ArtifactVO> artifacts,
    List<PushVO> pushes,
    StageCosts stageCosts,
    boolean sqlVisible) {
}
