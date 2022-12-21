package gov.cdc.dex.mrr

import gov.cdc.dex.azure.RedisProxy
import org.junit.jupiter.api.Test



internal class EventCodeClientTest {

    @Test
    fun loadEventMapsTest() {
        val eventCodes  = EventCodeClient()
        val redisName =  System.getenv("REDIS_CACHE_NAME")
        val redisKey = System.getenv("REDIS_CACHE_KEY")
        val redisProxy = RedisProxy(redisName, redisKey)
        eventCodes.loadEventMaps(redisProxy)
    }
    @Test
    fun loadGroupsTest() {
        val groupClient  = EventCodeClient()
        groupClient.loadGroups()
    }
}