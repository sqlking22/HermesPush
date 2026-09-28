package com.hermes.push.storage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hermes.push.common.BizException;
import com.hermes.push.common.ErrorCode;
import com.hermes.push.security.AesGcmCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StorageConfigService {
    private final StorageMapper mapper;
    private final AesGcmCipher cipher;

    public StorageConfigService(StorageMapper mapper, AesGcmCipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    @Transactional
    public Long save(String storageKey, String displayName, String type,
        String endpoint, String region, String bucket, String prefix,
        String accessKey, String secretKey, boolean enabled, String operator) {
        Storage s = new Storage();
        s.setStorageKey(storageKey);
        apply(s, displayName, type, endpoint, region, bucket, prefix, accessKey, secretKey, enabled);
        s.setEnabled(enabled ? 1 : 0);
        s.setCreatedBy(operator);
        try {
            mapper.insert(s);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.STO_004, "存储键已存在: " + storageKey);
        }
        return s.getId();
    }

    @Transactional
    public void update(Long id, String displayName, String type, String endpoint,
        String region, String bucket, String prefix, String accessKey, String secretKey,
        boolean enabled, String operator) {
        Storage s = require(id);
        apply(s, displayName, type, endpoint, region, bucket, prefix, accessKey, secretKey, enabled);
        s.setEnabled(enabled ? 1 : 0);
        mapper.updateById(s);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
    }

    public List<StorageVO> list() {
        return mapper.selectList(new QueryWrapper<Storage>().orderByAsc("id"))
            .stream().map(this::toVO).toList();
    }

    public StorageVO get(Long id) {
        return toVO(require(id));
    }

    /** 按 storageKey 取解密配置；不存在或停用抛 STO_001。 */
    public DecryptedStorage requireDecrypted(String storageKey) {
        Storage s = mapper.selectOne(new QueryWrapper<Storage>().eq("storage_key", storageKey));
        if (s == null || (s.getEnabled() != null && s.getEnabled() == 0)) {
            throw new BizException(ErrorCode.STO_001, "存储后端不存在或未启用: " + storageKey);
        }
        return new DecryptedStorage(
            s.getId(), s.getStorageKey(), s.getDisplayName(),
            StorageType.valueOf(s.getStorageType()),
            s.getEndpoint(), s.getRegion(), s.getBucket(), s.getPrefix(),
            decrypt(s.getAccessKeyEnc()), decrypt(s.getSecretKeyEnc()));
    }

    private void apply(Storage s, String displayName, String type, String endpoint,
        String region, String bucket, String prefix, String accessKey, String secretKey, boolean enabled) {
        s.setDisplayName(displayName);
        s.setStorageType(type);
        s.setEndpoint(endpoint);
        s.setRegion(region);
        s.setBucket(bucket);
        s.setPrefix(prefix);
        // 密钥留空 = 不修改（与 M1 渠道 webhook 一致）
        if (accessKey != null && !accessKey.isBlank()) s.setAccessKeyEnc(cipher.encrypt(accessKey));
        if (secretKey != null && !secretKey.isBlank()) s.setSecretKeyEnc(cipher.encrypt(secretKey));
    }

    private StorageVO toVO(Storage s) {
        return new StorageVO(s.getId(), s.getStorageKey(), s.getDisplayName(),
            s.getStorageType(), s.getEndpoint(), s.getRegion(), s.getBucket(),
            s.getPrefix(), s.getEnabled());
    }

    private Storage require(Long id) {
        Storage s = mapper.selectById(id);
        if (s == null) throw new BizException(ErrorCode.STO_001, "存储后端不存在: " + id);
        return s;
    }

    private String decrypt(String enc) {
        return (enc == null || enc.isBlank()) ? null : cipher.decrypt(enc);
    }
}
