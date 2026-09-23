package com.hermes.push.channel;

import java.util.List;

public record PushMessage(String msgType, String content, List<String> mentionedList) {}
