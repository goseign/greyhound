package com.wixpress.dst.greyhound.core

import org.apache.kafka.common.serialization.{Deserializer => KafkaDeserializer}
import zio.{Chunk, Task}

trait Deserializer[+A] {
  def deserialize(topic: TopicName, headers: Headers, data: Chunk[Byte]): Task[A]

  def flatMap[B](f: A => Deserializer[B]): Deserializer[B] =
    (topic: TopicName, headers: Headers, data: Chunk[Byte]) =>
      deserialize(topic, headers, data).flatMap { a =>
        f(a).deserialize(topic, headers, data)
      }

  def map[B](f: A => B): Deserializer[B] =
    (topic: TopicName, headers: Headers, data: Chunk[Byte]) =>
      deserialize(topic, headers, data).map(f)

  def zip[B](other: Deserializer[B]): Deserializer[(A, B)] =
    (topic: TopicName, headers: Headers, data: Chunk[Byte]) =>
      deserialize(topic, headers, data) zip
        other.deserialize(topic, headers, data)

  def orElse[A1 >: A](other: => Deserializer[A1]): Deserializer[A1] =
    (topic: TopicName, headers: Headers, data: Chunk[Byte]) =>
      deserialize(topic, headers, data) orElse
        other.deserialize(topic, headers, data)
}

object Deserializer {
  def apply[A](deserializer: KafkaDeserializer[A]): Deserializer[A] =
    (topic: TopicName, _: Headers, data: Chunk[Byte]) =>
      Task(deserializer.deserialize(topic, data.toArray))

  def apply[A](f: (TopicName, Headers, Chunk[Byte]) => Task[A]): Deserializer[A] =
    (topic: TopicName, headers: Headers, data: Chunk[Byte]) =>
      f(topic, headers, data)

  def apply[A](a: Task[A]): Deserializer[A] =
    (_: TopicName, _: Headers, _: Chunk[Byte]) => a
}