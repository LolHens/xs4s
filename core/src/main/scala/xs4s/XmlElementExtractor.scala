package xs4s

import javax.xml.stream.events.{EndElement, StartElement, XMLEvent}
import xs4s.ScalaXmlElemBuilder.{FinalElemScala, NoElem$Scala}

import scala.xml.Elem

object XmlElementExtractor {

  def filterElementsByName(name: String): XmlElementExtractor[Elem] =
    filterElementsByPredicate(elementTree =>
      elementTree.lastOption.map(_.getName.getLocalPart).contains(name))

  def filterElementsByPredicate[T](
      p: List[StartElement] => Boolean): XmlElementExtractor[Elem] =
    XmlElementExtractor(lse => if (p(lse)) Some(identity) else None)

  def collectWithPartialFunctionOfElementNames[T](
      pf: PartialFunction[List[String], Elem => T]): XmlElementExtractor[T] =
    XmlElementExtractor[T](l => pf.lift(l.map(_.getName.getLocalPart)))

  def collectWithPartialFunction[T](
      pf: PartialFunction[List[StartElement], Elem => T])
    : XmlElementExtractor[T] =
    XmlElementExtractor[T](l => pf.lift(l))

}

/**
  * The [[XmlElementExtractor]] continuously builds a tree of XML elements, and once a
  * desired match is found (as specified by extractionFunction), it will materialise the rest of the element,
  * and then call a function to transform it.
  *
  * The reason to return a [[T]] rather than a plain {{{Option[Elem]}}} is because
  * the function Elem => T may actually depend on the StartElement, for example:
  * if we have 2 types of StartElements we want to capture, we would like to return a different
  * function to process them, and then for example, return an {{{Either[X, Y]}}}.
  * Basically we want immediate processing here.
  */
final case class XmlElementExtractor[T](
    extractionFunction: List[StartElement] => Option[Elem => T]) {

  def initial: EventProcessor = EventProcessor.initial

  def map[V](f: T => V): XmlElementExtractor[V] = XmlElementExtractor(
    extractionFunction =
      list => extractionFunction(list).map(of => elem => f(of(elem)))
  )

  sealed trait EventProcessor {
    def process(xmlElemv: XMLEvent): Option[EventProcessor]
  }

  object Scan extends Scanner[XMLEvent, EventProcessor, T] {
    def initial: EventProcessor = EventProcessor.initial

    def scan(eventProcessor: EventProcessor,
             xMLEvent: XMLEvent): EventProcessor =
      eventProcessor.process(xMLEvent).getOrElse(eventProcessor)

    def collect(eventProcessor: EventProcessor): Option[T] =
      PartialFunction.condOpt(eventProcessor) {
        case EventProcessor.Captured(_, e) => e
      }
  }

  object EventProcessor {

    def initial: EventProcessor = ProcessingStack()

    final case class Captured(stack: List[StartElement], data: T)
        extends EventProcessor {
      def process(xmlEvent: XMLEvent): Option[EventProcessor] =
        ProcessingStack(stack.dropRight(1): _*).process(xmlEvent)
    }

    final case class Capturing(stack: List[StartElement],
                               state: ScalaXmlElemBuilder,
                               callback: Elem => T)
        extends EventProcessor {
      def process(xmlEvent: XMLEvent): Option[EventProcessor] = Option {
        state.process(xmlEvent) match {
          case FinalElemScala(elem) =>
            Captured(stack, callback(elem))
          case other =>
            Capturing(stack, other, callback)
        }
      }
    }

    final case class ProcessingStack(stack: StartElement*)
        extends EventProcessor {
      def process(xmlEvent: XMLEvent): Option[EventProcessor] =
        PartialFunction.condOpt(xmlEvent) {
          case startElement: StartElement =>
            val newStack = stack ++ Seq(startElement)
            extractionFunction(newStack.toList)
              .map { f =>
                Capturing(
                  stack = newStack.toList,
                  state = NoElem$Scala.process(startElement),
                  callback = f
                )
              }
              .getOrElse(ProcessingStack(newStack: _*))
          case _: EndElement =>
            val newStack = stack.dropRight(1)
            if (newStack.isEmpty) {
              FinishedProcessing
            } else {
              ProcessingStack(newStack: _*)
            }
          case _ => this
        }
    }

    case object FinishedProcessing extends EventProcessor {
      override def process(xmlElemv: XMLEvent): Option[EventProcessor] =
        Some(FinishedProcessing)
    }

  }

}
