# Spexflix Server

## Overview

Spexflix Server is the backend of Spexflix, FGV's video service for the recordings of the spex. It is a Spring Boot
application with a GraphQL API and plain HTTP endpoints for posters and for poster, subtitle and resumable (tus)
uploads. A worker, the same image with `API_WORKER_ENABLED=true`, processes uploads into HLS with ffmpeg, and the
media server (`spexflix-media`, nginx) serves the video from signed, expiring addresses. It is backed by MySQL, a media
folder and Keycloak ([fgv-keycloak][1]) for authentication, reads spex from Spexregister and links chapters to
Spexify's tracks. [Spexflix Web][2] is its frontend and [fgv-spexflix-deploy][3] runs it on a server.

The product spec is in [fgv-spexflix-spec][4].

## Learning more

To learn more about Spexflix Server, please consult the reference documentation, which is served at `/docs`. When
running locally, the reference documentation is available [here][10]. Its source is in `src/main/asciidoc`, and
`getting-started.adoc` describes how to run the whole stack locally with `docker compose`.

## Building from source

You will need Java 25 or later to build Spexflix Server, ffmpeg for the processing tests, and Docker for the
integration tests. It is built using [Gradle][5]:

```
./gradlew test              # unit tests, including a real transcode
./gradlew integrationTest   # integration tests, against MySQL and Keycloak in Testcontainers
./gradlew build             # both, plus the reference documentation
```

## Published images

CI builds the image with Jib and publishes `bloomper/fgv-spexflix-server` for `linux/amd64` and `linux/arm64`.
Since the worker needs ffmpeg, Jib builds on `bloomper/fgv-spexflix-server-base:25` (`eclipse-temurin:25-jre` plus
ffmpeg, from `base/Dockerfile`), which CI rebuilds and publishes first. The same image runs as API and as worker:

| Trigger              | Tags                              |
|----------------------|-----------------------------------|
| push to `develop`    | `develop`, `sha-<commit>`         |
| push of tag `vX.Y.Z` | `X.Y.Z`, `latest`, `sha-<commit>` |

To release, set `version` in `build.gradle` to `X.Y.Z`, merge to `main`, then `git tag vX.Y.Z && git push origin vX.Y.Z`.
The build fails if the tag doesn't match the version or if `X.Y.Z` is already on Docker Hub.

## Contributing

Contributors to this project agree to uphold its [code of conduct][6].

You can contribute to Spexflix Server by:

- Opening a [pull request][7]. Please see the [contributor guidelines][8] for details

## License

Spexflix Server is open source software released under the [Apache 2.0 license][9].

[1]: https://github.com/bloomper/fgv-keycloak
[2]: https://github.com/bloomper/fgv-spexflix-web
[3]: https://github.com/bloomper/fgv-spexflix-deploy
[4]: https://github.com/bloomper/fgv-spexflix-spec
[5]: https://gradle.org
[6]: CODE_OF_CONDUCT.md
[7]: https://help.github.com/articles/using-pull-requests/
[8]: CONTRIBUTING.md
[9]: https://www.apache.org/licenses/LICENSE-2.0.html
[10]: https://api.spexflix.fgv.local.nu:8443/docs/index.html
