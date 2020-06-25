package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core.consumer.RetryDecision.{NoMoreRetries, RetryWith}
import com.wixpress.dst.greyhound.core.producer.Producer
import zio.clock.{Clock, sleep}
import zio.duration._
import zio.{Chunk, ZIO}

object RetryRecordHandler {
  /**
   * Return a handler with added retry behavior based on the provided `RetryPolicy`.
   * Upon failures, the `producer` will be used to send the failing records to designated
   * retry topics where the handling will be retried, after an optional delay. This
   * allows the handler to keep processing records in the original topic - however,
   * ordering will be lost for retried records!
   */
  def withRetries[R2, R, E, K, V](handler: RecordHandler[R, E, K, V],
                                      retryPolicy: RetryPolicy, producer: Producer,
                                      subscription: ConsumerSubscription)
                                     (implicit evK: K <:< Chunk[Byte], evV: V <:< Chunk[Byte]): RecordHandler[R with R2 with Clock, Nothing, K, V] =
    (record: ConsumerRecord[K, V]) => {
      retryPolicy.retryAttempt(record.topic, record.headers, subscription).flatMap { retryAttempt =>
        ZIO.foreach_(retryAttempt)(_.sleep) *> handler.handle(record).catchAll {
          case Right(_: NonRetryableException) => ZIO.unit
          case error => retryPolicy.retryDecision(retryAttempt, record.bimap(evK, evV), error, subscription) flatMap {
            case RetryWith(retryRecord) =>
              producer.produce(retryRecord).tapError(_ => sleep(5.seconds)).eventually
            case NoMoreRetries =>
              ZIO.unit //todo: report uncaught errors and producer failures
          }
        }
      }
    }
}
