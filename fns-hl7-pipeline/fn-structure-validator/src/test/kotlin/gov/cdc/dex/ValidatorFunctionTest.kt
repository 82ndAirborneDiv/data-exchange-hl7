package gov.cdc.dex

import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.HttpRequestMessage
import gov.cdc.dex.azure.EventHubMetadata
import gov.cdc.dex.validation.structure.ValidatorFunction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.logging.Logger
import org.mockito.Mockito.mock

class ValidatorFunctionTest {

    private fun processFile(filename: String) {
        println("Start processing $filename ")
        val text = this::class.java.getResource("/$filename").readText()
        val messages = listOf(text)

        val eventHubMDList = listOf(EventHubMetadata(1, 99, "", ""))

        val function = ValidatorFunction()
        function.run(messages, eventHubMDList, getExecutionContext())
        println("Finished processing $filename ")

    }

    @Test
    fun processELR_HappyPath() {
        processFile("ELR_message.txt")
        assert(true)
    }

    @Test
    fun processELR_ExceptionPath() {
        processFile("ELR_Exceptionmessage.txt")
        assert(true)
    }

    @Test
    fun processCASE_HappyPath() {
        processFile("CASE_message.txt")
        assert(true)
    }

    @Test
    fun invoke_test(){
        val function = ValidatorFunction()
        val req: HttpRequestMessage<Optional<String>> = mock(HttpRequestMessage::class.java) as HttpRequestMessage<Optional<String>>
        assertThrows<NullPointerException> { function.invoke(req) }
    }


    private fun getExecutionContext(): ExecutionContext {
        return object : ExecutionContext {
            override fun getLogger(): Logger {
                return Logger.getLogger(ValidatorFunction::class.java.name)
            }

            override fun getInvocationId(): String {
                return "null"
            }

            override fun getFunctionName(): String {
                return "null"
            }
        }
    }
}