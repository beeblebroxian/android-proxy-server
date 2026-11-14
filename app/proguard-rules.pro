# Netty's internals rely on reflection; keep them intact until device-tested under R8.
-keep class io.netty.** { *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions
