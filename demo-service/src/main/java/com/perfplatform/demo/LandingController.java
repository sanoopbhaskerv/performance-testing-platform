package com.perfplatform.demo;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * A page at the root, so the demo service has somewhere to land.
 *
 * <p>The service is otherwise API-only, and an API-only service is an awkward thing to demonstrate a
 * browser recorder against: a recording starts at the target's base URL, so it opened on a bare
 * error page and the first thing anyone saw was a failure.
 *
 * <p>Deliberately hand-written HTML with no build step and no template engine. It exists to be
 * clicked — the links below are what turn "start a recording" into a journey with steps in it, which
 * is exactly what the recorder needs to demonstrate. {@code demo-student-ui} remains the real
 * client; this is a doorway, not a replacement for it.
 */
@Controller
public class LandingController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String index() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Student registry — demo service</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body {
                      font: 16px/1.6 system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
                      margin: 0; padding: 3rem 1.5rem; display: flex; justify-content: center;
                    }
                    main { max-width: 40rem; width: 100%; }
                    h1 { font-size: 1.5rem; margin: 0 0 .25rem; }
                    p.lede { margin: 0 0 2rem; opacity: .75; }
                    ul { list-style: none; padding: 0; margin: 0 0 2rem; }
                    li { margin: 0 0 .75rem; }
                    a {
                      display: block; padding: .85rem 1rem; border: 1px solid;
                      border-radius: .5rem; text-decoration: none; color: inherit;
                      border-color: color-mix(in srgb, currentColor 25%, transparent);
                    }
                    a:hover { border-color: currentColor; }
                    a code { font-size: .85rem; opacity: .7; display: block; margin-top: .15rem; }
                    footer { font-size: .85rem; opacity: .6; }
                  </style>
                </head>
                <body>
                  <main>
                    <h1>Student registry</h1>
                    <p class="lede">
                      The demo service the platform load-tests against. Click through to record a
                      journey.
                    </p>
                    <ul>
                      <li>
                        <a href="/api/students">Every student
                          <code>GET /api/students</code></a>
                      </li>
                      <li>
                        <a href="/api/students/1">One student
                          <code>GET /api/students/{id}</code></a>
                      </li>
                      <li>
                        <a href="/actuator/health">Health
                          <code>GET /actuator/health</code></a>
                      </li>
                    </ul>
                    <footer>
                      Creating and updating a student are <code>POST</code> and <code>PUT</code> on
                      the same paths.
                    </footer>
                  </main>
                </body>
                </html>
                """;
    }
}
