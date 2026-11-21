package com.aid.auth.service;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import cn.hutool.core.util.StrUtil;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ip.IpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 统一校验短信与邮箱验证码。
 * @author 视觉AID
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationCodeService {
    private static final String ATTEMPT_PREFIX = "auth:code:attempt:";
    private static final String LOGIN_RESERVATION_PREFIX = "auth:code:login:";
    private static final long LOGIN_RESERVATION_SECONDS = 120;

    private final RedisCache redisCache;

    public void verify(String cacheKey, String code, boolean consume) {
        if (StrUtil.isBlank(code)) {
            throw new ServiceException("验证码不能为空");
        }
        long result = redisCache.verifyCode(cacheKey, ATTEMPT_PREFIX + cacheKey.toLowerCase(Locale.ROOT),
                ATTEMPT_PREFIX + "ip:" + IpUtils.getIpAddr(), code, consume);
        if (result == -3) {
            throw new ServiceException("验证请求过多");
        }
        if (result == -2) {
            throw new ServiceException("验证码尝试过多");
        }
        if (result == -1) {
            throw new ServiceException("验证码已过期");
        }
        if (result != 1) {
            throw new ServiceException("验证码错误");
        }
    }

    /** 登录事务回滚时保留验证码，允许用户修正邀请码后重试。 */
    public void reserveForLogin(String cacheKey, String code) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("验证码登录需要事务");
        }
        String reservationKey = LOGIN_RESERVATION_PREFIX + cacheKey.toLowerCase(Locale.ROOT);
        String reservation = UUID.randomUUID().toString();
        if (!redisCache.setCacheObjectIfAbsent(reservationKey, reservation, LOGIN_RESERVATION_SECONDS,
                TimeUnit.SECONDS)) {
            throw new ServiceException("登录正在处理");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                redisCache.deleteObjectIfValueEquals(reservationKey, reservation);
            }
        });
        verify(cacheKey, code, false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!redisCache.deleteObjectIfValueEquals(cacheKey, code)) {
                    log.info("登录验证码已失效");
                    throw new ServiceException("验证码已失效");
                }
            }
        });
    }
}
