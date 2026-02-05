package org.springframework.samples.petclinic.imperative

import com.jetbrains.kotlinllm.asLlm
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.Locale

internal fun ServerRequest.pathInt(name: String): Int =
    asLlm<RouteValue, Int>(
        RouteValue(name, pathVariable(name)),
        "Parse the route variable named '$name' as an Int."
    )

internal fun ServerRequest.parameters(): RequestParameters =
    RequestParameters(servletRequest().parameterMap.mapValues { entry -> entry.value.toList() })

internal fun ServerRequest.locale(): String =
    servletRequest().locale?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()

internal fun redirect(location: String): ServerResponse =
    ServerResponse.status(303).header("Location", location).build()

internal fun html(title: String, body: String): ServerResponse =
    ServerResponse.ok()
        .contentType(MediaType.TEXT_HTML)
        .body(
            """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>${escape(title)}</title>
                <style>
                  body { font-family: system-ui, sans-serif; margin: 2rem; line-height: 1.45; color: #1f2933; }
                  nav, .actions { display: flex; flex-wrap: wrap; gap: .75rem; margin: 1rem 0; }
                  a { color: #075985; }
                  table { border-collapse: collapse; width: 100%; margin: 1rem 0; }
                  th, td { border-bottom: 1px solid #d8dee4; padding: .55rem; text-align: left; vertical-align: top; }
                  label { display: block; margin: .75rem 0 .25rem; font-weight: 600; }
                  input, select { min-width: 18rem; padding: .45rem; }
                  button, .button { display: inline-block; margin-top: 1rem; padding: .5rem .8rem; border: 1px solid #075985; background: #075985; color: white; text-decoration: none; border-radius: .25rem; }
                  .error { color: #b91c1c; margin: .25rem 0; }
                  .muted { color: #64748b; }
                </style>
              </head>
              <body>
                <nav>
                  <a href="/">Home</a>
                  <a href="/owners/find">Owners</a>
                  <a href="/vets.html">Veterinarians</a>
                </nav>
                $body
              </body>
            </html>
            """.trimIndent()
        )

internal fun escape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
