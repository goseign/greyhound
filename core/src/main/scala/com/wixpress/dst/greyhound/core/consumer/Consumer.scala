package com.wixpress.dst.greyhound.core.consumer

import java.util.regex.Pattern
import java.{time, util}

import com.wixpress.dst.greyhound.core.consumer.Consumer._
import com.wixpress.dst.greyhound.core._
import com.wixpress.dst.greyhound.core.consumer.domain.{ConsumerRecord, TopicPartition}
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetrics
import org.apache.kafka.clients.consumer.{ConsumerRebalanceListener, KafkaConsumer, OffsetAndMetadata, ConsumerConfig => KafkaConsumerConfig}
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.{TopicPartition => KafkaTopicPartition}
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.duration.Duration

import scala.collection.JavaConverters._
import scala.util.Random

trait Consumer {
  def subscribe[R1](topics: Set[Topic], rebalanceListener: RebalanceListener[R1] = RebalanceListener.Empty): RIO[Blocking with GreyhoundMetrics with R1, Unit]

  def subscribePattern[R1](topicStartsWith: Pattern, rebalanceListener: RebalanceListener[R1] = RebalanceListener.Empty): RIO[Blocking with GreyhoundMetrics with R1, Unit]

  def poll(timeout: Duration): RIO[Blocking with GreyhoundMetrics, Records]

  def commit(offsets: Map[TopicPartition, Offset]): RIO[Blocking with GreyhoundMetrics, Unit]

  def commitOnRebalance(offsets: Map[TopicPartition, Offset]): RIO[Blocking with GreyhoundMetrics, DelayedRebalanceEffect]

  def pause(partitions: Set[TopicPartition]): ZIO[Blocking with GreyhoundMetrics, IllegalStateException, Unit]

  def resume(partitions: Set[TopicPartition]): ZIO[Blocking with GreyhoundMetrics, IllegalStateException, Unit]

  def seek(partition: TopicPartition, offset: Offset): ZIO[Blocking with GreyhoundMetrics, IllegalStateException, Unit]

  def pause(record: ConsumerRecord[_, _]): ZIO[Blocking with GreyhoundMetrics, IllegalStateException, Unit] = {
    val partition = TopicPartition(record)
    pause(Set(partition)) *> seek(partition, record.offset)
  }

  def assignment: Task[Set[TopicPartition]]
}

object Consumer {
  type Record = ConsumerRecord[Chunk[Byte], Chunk[Byte]]
  type Records = Iterable[Record]

  private val deserializer = new Deserializer[Chunk[Byte]] {
    override def configure(configs: util.Map[Topic, _], isKey: Boolean): Unit = ()

    override def deserialize(topic: Topic, data: Array[Byte]): Chunk[Byte] = Chunk.fromArray(data)

    override def close(): Unit = ()
  }

  def make(config: ConsumerConfig): RManaged[Blocking, Consumer] = for {
    semaphore <- Semaphore.make(1).toManaged_
    consumer <- makeConsumer(config)
  } yield new Consumer {
    override def subscribePattern[R1](pattern: Pattern, rebalanceListener: RebalanceListener[R1]): RIO[Blocking with R1, Unit] =
      listener(rebalanceListener *> config.additionalListener).flatMap(lis => withConsumer(_.subscribe(pattern, lis)))

    override def subscribe[R1](topics: Set[Topic], rebalanceListener: RebalanceListener[R1]): RIO[Blocking with R1, Unit] =
      listener(rebalanceListener *> config.additionalListener).flatMap(lis => withConsumerBlocking(_.subscribe(topics.asJava, lis)))

    override def poll(timeout: Duration): RIO[Blocking, Records] =
      withConsumerBlocking {c =>
        c.poll(time.Duration.ofMillis(timeout.toMillis)).asScala.map(ConsumerRecord(_))
      }


    override def commit(offsets: Map[TopicPartition, Offset]): RIO[Blocking with GreyhoundMetrics, Unit] = {
      withConsumerBlocking(_.commitSync(kafkaOffsets(offsets)))
    }

    override def commitOnRebalance(offsets: Map[TopicPartition, Offset]): RIO[Blocking with GreyhoundMetrics, DelayedRebalanceEffect] = {
      val kOffsets = kafkaOffsets(offsets)
      // we can't actually call commit here, as it needs to be called from the same
      // thread, that triggered poll(), so we return the commit action as thunk
      UIO(DelayedRebalanceEffect(consumer.commitSync(kOffsets)))
    }

    override def pause(partitions: Set[TopicPartition]): ZIO[Any, IllegalStateException, Unit] =
      withConsumer(_.pause(kafkaPartitions(partitions))).refineOrDie {
        case e: IllegalStateException => e
      }

    override def resume(partitions: Set[TopicPartition]): ZIO[Any, IllegalStateException, Unit] =
      withConsumer(consumer => {
        val onlySubscribed = consumer.assignment().asScala.toSet intersect kafkaPartitions(partitions).asScala.toSet
        consumer.resume(onlySubscribed.asJavaCollection)
      }).refineOrDie {
        case e: IllegalStateException => e
      }

    override def seek(partition: TopicPartition, offset: Offset): ZIO[Any, IllegalStateException, Unit] =
      withConsumer(_.seek(new KafkaTopicPartition(partition.topic, partition.partition), offset)).refineOrDie {
        case e: IllegalStateException => e
      }

    override def assignment: Task[Set[TopicPartition]] = {
      withConsumer(_.assignment().asScala.toSet.map(TopicPartition.apply(_: org.apache.kafka.common.TopicPartition)))
    }

    private def withConsumer[A](f: KafkaConsumer[Chunk[Byte], Chunk[Byte]] => A): Task[A] =
      semaphore.withPermit(Task(f(consumer)))

    private def withConsumerBlocking[A](f: KafkaConsumer[Chunk[Byte], Chunk[Byte]] => A): RIO[Blocking, A] =
      semaphore.withPermit(effectBlocking(f(consumer)))

    private def kafkaOffsets(offsets: Map[TopicPartition, Offset]): util.Map[KafkaTopicPartition, OffsetAndMetadata] =
      offsets.foldLeft(new util.HashMap[KafkaTopicPartition, OffsetAndMetadata](offsets.size)) {
        case (acc, (TopicPartition(topic, partition), offset)) =>
          val key = new KafkaTopicPartition(topic, partition)
          val value = new OffsetAndMetadata(offset)
          acc.put(key, value)
          acc
      }

    private def kafkaPartitions(partitions: Set[TopicPartition]): util.Collection[KafkaTopicPartition] =
      partitions.foldLeft(new util.ArrayList[KafkaTopicPartition](partitions.size)) {
        case (acc, TopicPartition(topic, partition)) =>
          acc.add(new KafkaTopicPartition(topic, partition))
          acc
      }

  }

  private def listener[R1](rebalanceListener: RebalanceListener[R1]) =
    ZIO.runtime[Blocking with R1].map { runtime =>
      new ConsumerRebalanceListener {
        override def onPartitionsRevoked(partitions: util.Collection[KafkaTopicPartition]): Unit = {
          runtime.unsafeRun(rebalanceListener.onPartitionsRevoked(partitionsFor(partitions)))
            .run() // this needs to be run in the same thread
        }

        override def onPartitionsAssigned(partitions: util.Collection[KafkaTopicPartition]): Unit =
          runtime.unsafeRun(rebalanceListener.onPartitionsAssigned(partitionsFor(partitions)))

        private def partitionsFor(partitions: util.Collection[KafkaTopicPartition]) =
          partitions.asScala.map(TopicPartition(_)).toSet
      }
    }

  private def makeConsumer(config: ConsumerConfig): RManaged[Blocking, KafkaConsumer[Chunk[Byte], Chunk[Byte]]] = {
    val acquire = effectBlocking(new KafkaConsumer(config.properties, deserializer, deserializer))
    ZManaged.make(acquire)(consumer => effectBlocking(consumer.close()).ignore)
  }


}

case class ConsumerConfig(bootstrapServers: String,
                          groupId: Group,
                          clientId: ClientId = s"wix-consumer-${Random.alphanumeric.take(5).mkString}",
                          offsetReset: OffsetReset = OffsetReset.Latest,
                          extraProperties: Map[String, String] = Map.empty,
                          additionalListener: RebalanceListener[Any] = RebalanceListener.Empty) extends CommonGreyhoundConfig {


  override def kafkaProps: Map[String, String] = Map(
    KafkaConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers,
    KafkaConsumerConfig.GROUP_ID_CONFIG -> groupId,
    KafkaConsumerConfig.CLIENT_ID_CONFIG -> clientId,
    (KafkaConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset match {
      case OffsetReset.Earliest => "earliest"
      case OffsetReset.Latest => "latest"
    }),
    KafkaConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false"
  ) ++ extraProperties

  def withExtraProperties(props: (String, String)*) =
    copy(extraProperties = extraProperties ++ props)

}

sealed trait OffsetReset

object OffsetReset {

  case object Earliest extends OffsetReset

  case object Latest extends OffsetReset

}
