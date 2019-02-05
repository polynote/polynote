package polynote.kernel.util

import cats.effect.IO
import cats.effect.concurrent.Deferred

// TODO: trying to centralize how tasks are tracked.
class TaskManager {

  def add(id: String, cancel: () => IO[Unit]): Unit = {

  }

  private case class Task(id: String, title: String, description: String, ticks: Int, cancel: () => IO[Unit])
}
