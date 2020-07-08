// tracing.groovy
// A sysdig-tracing inspired tracer for jenkins.
//
// Anatomy of a trace
//
// Like sysdig, we manage traces by emitting specially formatted lines. In our
// case, we emit them to stdout.
//
// A tracer is akin to a marker for a point in time; and they come in pairs: an
// entry point and an exit point. The span, then, is the unit of time between
// the entry and exit. Spans can be nested, and the root of a span tree is
// called a trace.
//
// The format of a trace is simple plain text:
//
// `SPAN-START:<unix milliseconds>:<parent id>:<id>:<name>:<args>:`
//
// For exit spans, replace `SPAN-START` with `SPAN-END`.
//
// `<unix milliseconds>` is the number of milliseconds since the unix epoch.
//
// `<parent id>` is the id of the parent span when you have nested spans. The
// root span can have an empty parent id to indicate it has no parents (and is
// at the top of the span tree).
//
// `<id>` is the id of the current span. Each span should ideally have a unique
// id, but the more strict requirement is that the pair (parent, child) is
// unique to avoid mixing two spans together.
//
// `<name>` is the name of the span. It's presented in trace viewers and can
// also be used to look at timing across traces for the same type-of-span.
//
// `<args>` is a set of `key=value` pairs, separated by a comma, that can be
// associated with the span.  This particular implementation only emits args on
// the exit line.
class Span implements Serializable {
   String parentID;
   String ID;
   String name;

   def args;

   Span(String name) {
      this.ID = this.makeID();
      this.name = name;
      this.args = [];
   }

   // Returns a 16-byte string of 8 random bytes in hex encoding, suitable for
   // use as a span ID
   @NonCPS
   String makeID() {
      byte[] bytes = new Random().ints().limit(8).toArray();
      return bytes.encodeHex().toString();
   }

   String startline() {
      return "SPAN-START:${this._format()}";
   }

   String endline() {
      return "SPAN-END:${this._format()}";
   }

   String _format() {
      def ts = System.currentTimeMillis();
      return "${ts}:${this.parentID}:${this.ID}:${this.name}:${this.formatArgs()}:";
   }

   String formatArgs() {
      def rv = [];
      for (def a in this.args) {
         rv.push("${a[0]}=\"${a[1]}\"");
      }
      return rv.join(",");
   }

   def arg(String key, Object value) {
      // TODO(avidal): Escape " within the value
      this.args << [key, "${value}"];
   }

   // Return a new child span with the parent ID pre-initialized
   Span child(String name) {
      Span _child = new Span(name);
      _child.parentID = this.ID;
      return _child;
   }
}

// Primary entrypoint to the tracing library.
// Callers will generally create a span by using
// `tracing.withSpan(parent, name) { /* body */ }`
// If this is the root span, you can specify `null` for the parent.
//
// Note that your closure (the /* body */) above will receive the span as the
// first argument. Due to groovy scoping rules within closures, you cannot
// define a variable inside of a closure that shadows the name of a variable
// within the outer scope. This comes to play (quite often!) when you have
// nested spans. The *natural* way to do that is by using the name `span` as
// the closure parameter, but you cannot. Pick a different name, or use the
// magic name `it`, which every closure has by default as the first argument.
//
// When creating your span, you can supply an initial set of arguments by
// adding additional named parameters, which are all collected into the `args`
// Map, eg: `tracing.withSpan(null, name, foo: "bar", baz: 42) { /* body */ }`
def withSpan(Map kwargs, Span ctx = null, String name, Closure body) {
   if (ctx == null) {
      ctx = new Span("");
      // Reset the ID of the fake parent context to ensure it gets rooted to
      // the top of the span tree, instead of rooted to a non-existent span
      ctx.ID = "";
   }

   Span span = ctx.child(name);

   // If any additional named paramters are supplied, let's assign those as our
   // initial set of arguments
   for (def k in kwargs) {
      span.arg(k, kwargs[k]);
   }

   // We can't `echo` within the Span class above, not sure why. I think it's
   // because echo is a pipeline step? So we instead let it format the
   // start/end strings and then we echo it here instead.
   echo span.startline();
   try {
      body(span);
   } finally {
      // TODO(avidal): Catch exceptions and decorate the span and then
      // re-raise?
      echo span.endline();
   }
}
