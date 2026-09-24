package com.hermes.push.exec;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hermes.push.exec.vo.ExecListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ExecLogMapper extends BaseMapper<TaskExec> {

  IPage<ExecListVO> selectExecPage(Page<ExecListVO> page,
      @Param("taskId") Long taskId,
      @Param("status") String status,
      @Param("triggerType") String triggerType,
      @Param("from") LocalDateTime from,
      @Param("to") LocalDateTime to);

  List<Map<String, Object>> selectTodayCounts();
}
