package com.agent.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.api.request.ChannelModelRequest;
import com.agent.infrastructure.persistence.entity.ChannelModel;
import com.agent.infrastructure.persistence.mapper.ChannelModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChannelModelService {

    private final ChannelModelMapper channelModelMapper;

    public List<ChannelModel> listByChannelId(Long channelId) {
        LambdaQueryWrapper<ChannelModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChannelModel::getChannelId, channelId).orderByAsc(ChannelModel::getId);
        return channelModelMapper.selectList(wrapper);
    }

    @Transactional
    public ChannelModel createModel(Long channelId, ChannelModelRequest req) {
        ChannelModel m = ChannelModel.builder()
                .channelId(channelId)
                .modelValue(req.getModelValue())
                .displayName(req.getDisplayName())
                .createdAt(LocalDateTime.now())
                .build();
        channelModelMapper.insert(m);
        return m;
    }

    @Transactional
    public void deleteModel(Long modelId) {
        channelModelMapper.deleteById(modelId);
    }

    /**
     * Replace existing models for channel with provided list (delete old -> insert new)
     */
    @Transactional
    public List<ChannelModel> replaceModels(Long channelId, List<ChannelModelRequest> reqs) {
        // delete existing
        LambdaQueryWrapper<ChannelModel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChannelModel::getChannelId, channelId);
        channelModelMapper.delete(wrapper);

        // insert new
        List<ChannelModel> out = new ArrayList<>();
        if (reqs == null || reqs.isEmpty()) return out;

        for (ChannelModelRequest r : reqs) {
            ChannelModel m = ChannelModel.builder()
                    .channelId(channelId)
                    .modelValue(r.getModelValue())
                    .displayName(r.getDisplayName())
                    .createdAt(LocalDateTime.now())
                    .build();
            channelModelMapper.insert(m);
            out.add(m);
        }
        return out;
    }
}
