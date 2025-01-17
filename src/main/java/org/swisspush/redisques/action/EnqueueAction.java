package org.swisspush.redisques.action;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.swisspush.redisques.lua.LuaScriptManager;
import org.swisspush.redisques.util.MemoryUsageProvider;
import org.swisspush.redisques.util.QueueConfiguration;
import org.swisspush.redisques.util.QueueStatisticsCollector;

import java.util.Arrays;
import java.util.List;

import static org.swisspush.redisques.util.RedisquesAPI.*;

public class EnqueueAction extends AbstractQueueAction {

    private MemoryUsageProvider memoryUsageProvider;
    private int memoryUsageLimitPercent;

    public EnqueueAction(Vertx vertx, LuaScriptManager luaScriptManager, RedisAPI redisAPI, String address, String queuesKey, String queuesPrefix,
                                     String consumersPrefix, String locksKey, List<QueueConfiguration> queueConfigurations,
                                     QueueStatisticsCollector queueStatisticsCollector, Logger log, MemoryUsageProvider memoryUsageProvider, int memoryUsageLimitPercent) {
        super(vertx, luaScriptManager, redisAPI, address, queuesKey, queuesPrefix, consumersPrefix, locksKey, queueConfigurations,
                queueStatisticsCollector, log);
        this.memoryUsageProvider = memoryUsageProvider;
        this.memoryUsageLimitPercent = memoryUsageLimitPercent;
    }

    @Override
    public void execute(Message<JsonObject> event) {
        String queueName = event.body().getJsonObject(PAYLOAD).getString(QUEUENAME);

        if(isMemoryUsageLimitReached()) {
            log.warn("Failed to enqueue into queue {} because the memory usage limit is reached", queueName);
            event.reply(createErrorReply().put(MESSAGE, MEMORY_FULL));
            return;
        }
        updateTimestamp(queueName, null);
        String keyEnqueue = queuesPrefix + queueName;
        String valueEnqueue = event.body().getString(MESSAGE);
        redisAPI.rpush(Arrays.asList(keyEnqueue, valueEnqueue), event2 -> {
            JsonObject reply = new JsonObject();
            if (event2.succeeded()) {
                if (log.isDebugEnabled()) {
                    log.debug("RedisQues Enqueued message into queue {}", queueName);
                }
                long queueLength = event2.result().toLong();
                notifyConsumer(queueName);
                reply.put(STATUS, OK);
                reply.put(MESSAGE, "enqueued");

                // feature EN-queue slow-down (the larger the queue the longer we delay "OK" response)
                long delayReplyMillis = 0;
                QueueConfiguration queueConfiguration = findQueueConfiguration(queueName);
                if (queueConfiguration != null) {
                    float enqueueDelayFactorMillis = queueConfiguration.getEnqueueDelayFactorMillis();
                    if (enqueueDelayFactorMillis > 0f) {
                        // minus one as we need the queueLength _before_ our en-queue here
                        delayReplyMillis = (long) ((queueLength - 1) * enqueueDelayFactorMillis);
                        int max = queueConfiguration.getEnqueueMaxDelayMillis();
                        if (max > 0 && delayReplyMillis > max) {
                            delayReplyMillis = max;
                        }
                    }
                }
                if (delayReplyMillis > 0) {
                    vertx.setTimer(delayReplyMillis, timeIsUp -> event.reply(reply));
                } else {
                    event.reply(reply);
                }
                queueStatisticsCollector.setQueueBackPressureTime(queueName, delayReplyMillis);
            } else {
                String message = "RedisQues QUEUE_ERROR: Error while enqueueing message into queue " + queueName;
                log.error(message, event2.cause());
                reply.put(STATUS, ERROR);
                reply.put(MESSAGE, message);
                event.reply(reply);
            }
        });
    }

    protected boolean isMemoryUsageLimitReached() {
        if(memoryUsageProvider.currentMemoryUsagePercentage().isEmpty()) {
            return false;
        }
        return memoryUsageProvider.currentMemoryUsagePercentage().get() > memoryUsageLimitPercent;
    }
}
