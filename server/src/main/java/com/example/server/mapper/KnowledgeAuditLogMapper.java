package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.KnowledgeAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeAuditLogMapper extends BaseMapper<KnowledgeAuditLog> {
}
