package com.byd.easyshare.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import com.byd.easyshare.R
import com.byd.easyshare.exceptions.CancelledByUserException
import com.byd.easyshare.exceptions.ExceptionWithMessage
import com.byd.easyshare.models.TransferUiState
import com.byd.easyshare.models.TransferUiStatus
import com.byd.easyshare.outgoingTransferTitle
import com.byd.easyshare.utils.withTimeoutReason
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException

class OutgoingFailurePresentationTest {
    @Test
    fun rejectionLocalCancellationAndTimeoutUseDistinctResultTitles() {
        for ((error, status, title) in listOf(
            Triple(CancelledByUserException(true), TransferUiStatus.REJECTED, R.string.device_status_rejected),
            Triple(CancelledByUserException(false), TransferUiStatus.CANCELED, R.string.device_status_canceled),
            Triple(TimeoutException(), TransferUiStatus.TIMEOUT, R.string.device_status_timeout),
            Triple(UnconfirmedTransferException(), TransferUiStatus.UNCONFIRMED, R.string.device_status_unconfirmed),
        )) {
            assertEquals(status, outgoingFailureStatus(error))
            assertEquals(title, outgoingTransferTitle(TransferUiState(1, "peer", outgoingFailureStatus(error))))
        }
    }

    @Test(timeout = 5_000)
    fun stageTimeoutRetainsItsTimeoutClassificationAfterMessageWrapping() = runBlocking {
        try {
            withTimeoutReason(Duration.ofMillis(10), "connection", R.string.error_send_timeout_ws) {
                awaitCancellation()
            }
            fail("Expected the connection deadline")
        } catch (error: ExceptionWithMessage) {
            assertEquals(TransferUiStatus.TIMEOUT, outgoingFailureStatus(error))
        }
    }

    @Test
    fun unexpectedIoAndCoroutineCancellationAreNotReportedAsUserDecisions() {
        for (error in listOf(IOException(), CancellationException(), null)) {
            assertEquals(TransferUiStatus.FAILED, outgoingFailureStatus(error))
        }
    }
}
