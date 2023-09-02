package com.tiktok.feign_util.utils;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Repository
@FeignClient("service-feed")
public interface VideoFeignClient {
    @GetMapping("/douyin/inner/videonum")
    Integer getVideoNumByUserId(@RequestParam("userId") Long userId);

}
