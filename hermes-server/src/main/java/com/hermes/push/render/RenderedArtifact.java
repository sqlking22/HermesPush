package com.hermes.push.render;

/**
 * 渲染产物。
 *
 * @param key     artifact key
 * @param type    类型（如 MARKDOWN）
 * @param content 渲染后的内容
 * @param bytes   内容的 UTF-8 字节数
 */
public record RenderedArtifact(
    String key,
    String type,
    String content,
    int bytes) {
}
