# Spexflix Server

## Overview

Spexflix Server is the backend of Spexflix, FGV's video service for the recordings of the spex. It is a Spring Boot
application with a GraphQL API and plain HTTP endpoints for posters and for poster, subtitle and resumable (tus)
uploads. A worker, the same image with `API_WORKER_ENABLED=true`, processes uploads into HLS with ffmpeg. Video is
served by the media server (`spexflix-media`, nginx) from signed, expiring addresses, never by Java. It uses MySQL,
Keycloak ([fgv-keycloak][1]), Spexregister (spex, with the `MEDIA` role) and Spexify (tracks for chapters).
[Spexflix Web][2] is its frontend and [fgv-spexflix-deploy][3] runs it. The product spec is in [fgv-spexflix-spec][4].

## How it fits together

- **Storage.** Metadata lives in MySQL; files in the media folder, named by id:

  | Path                                   | Content                                                    |
  |----------------------------------------|------------------------------------------------------------|
  | `originals/<uuid>/original.<ext>`      | The uploaded file, the archive copy; never served          |
  | `originals/<uuid>/pending-<n>.<ext>`   | A replacement waiting to be processed                      |
  | `hls/<uuid>/<generation>/`             | `master.m3u8`, one folder per rendition, `thumbs/`, `poster.jpg` |
  | `hls/<uuid>/subtitles/<lang>.vtt`      | Subtitles, kept across generations                         |
  | `uploads/`                             | Unfinished uploads (kept 24 hours)                         |

- **Processing.** One job at a time, kept in the database so it survives restarts; a job whose lease runs out starts
  over. ffprobe reads the original; ffmpeg makes 1080p, 720p and 480p (never above the original), H.264 and AAC,
  6-second segments with aligned keyframes, deinterlacing and loudness normalisation. An H.264/AAC original of at most
  1080p is copied into the top rendition instead. Then timeline thumbnails (sprites and a WebVTT index) and a poster.
  A new generation replaces the old only when complete, so a replaced video keeps playing.
- **Playback.** `playback(videoId)` returns `MEDIA_BASE_URL/v/<expires>/<signature>/<uuid>/<generation>/master.m3u8`,
  with `signature = base64url(md5("<expires><uuid> <MEDIA_SIGNING_KEY>"))`, valid for the video's duration plus four
  hours. nginx's `secure_link` checks it for every file of that video.
- **Spexify.** Editors link chapters to Spexify's published tracks (`spexifyTracks`, through Spexify's `spexTracks` with
  the `SPEXFLIX` role). Spexify asks `chapterLinks(trackIds)` with the `SPEXIFY` role to show *Watch in Spexflix*.

## Running locally

Keycloak comes from `fgv-infra-deploy/local`, with the realm's `fgv-spexflix` and `fgv-spexflix-service` clients.
`docker-compose.yaml` here starts MySQL and the media server, whose configuration is
`../fgv-spexflix-deploy/media/default.conf.template`:

```shell
docker compose up -d
API_KEYCLOAK_SERVICE_CLIENT_SECRET=… API_KEYCLOAK_ADMIN_CLIENT_SECRET=… \
API_IMPORT_SAMPLE_DATA=true API_SPEXIFY_GRAPHQL_URL=http://localhost:8082/api/graphql \
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=<a truststore with the local Caddy CA>" \
./gradlew bootRun --args='--spring.profiles.active=local'
```

The API listens on 8083 and, in the `local` profile, also runs the worker; the media server on 8084. ffmpeg and
ffprobe must be on the path.

### Sample data

With `API_IMPORT_SAMPLE_DATA=true` the API replaces the database and empties the media folders at every start (it
refuses in the `production` profile). It generates originals with ffmpeg, one per path through processing (H.264
1080p copied, H.264 720p, VP9 re-encoded, interlaced MPEG-2 deinterlaced, a file that is not video), builds about 15
videos on Spexregister's sample spex with chapters (linked to Spexify's sample tracks when Spexify runs with its
sample data), subtitles, viewing history and audit entries, and queues them for the worker.
`API_SAMPLE_DATA_PREPROCESSED=true` processes each distinct original at once instead, so every video is ready when
the API has started; `API_SAMPLE_DATA_SIZE=large` makes about 300 videos.

Test credentials (password `SAMPLE_USER_PASSWORD`, default `s3cr3t-s3cr3t`):

| User                         | Roles on `fgv-spexflix`         |
|------------------------------|---------------------------------|
| `spexflix-user@fgv.nu`       | `USER`, with progress and a watch list |
| `spexflix-editor@fgv.nu`     | `USER`, `EDITOR`                |
| `spexflix-admin@fgv.nu`      | `USER`, `ADMIN`                 |
| `spexflix-no-access@fgv.nu`  | none                            |
| `spexify-spexflix@fgv.nu`    | `USER`, and `USER` on Spexify   |
| `admin@fgv.nu`               | `USER`, `ADMIN` (Spexregister's sample admin) |

## Building from source

Java 25, ffmpeg for the processing tests, and Docker for integration tests:

```
./gradlew test              # unit tests, including a real transcode
./gradlew check             # unit and integration tests
./gradlew bootJar           # build/libs/app.jar, which the Dockerfile packs with ffmpeg
```

## Published images

CI builds the image from the `Dockerfile` (ffmpeg is needed) and publishes `bloomper/fgv-spexflix-server` for
`linux/amd64` and `linux/arm64`: `develop` and `sha-<commit>` on pushes to `develop`, `X.Y.Z`, `latest` and
`sha-<commit>` on tags `vX.Y.Z`. The same image runs as API and as worker.

## License

Spexflix Server is Open Source software released under the [Apache 2.0 license][5].

[1]: https://github.com/bloomper/fgv-keycloak
[2]: https://github.com/bloomper/fgv-spexflix-web
[3]: https://github.com/bloomper/fgv-spexflix-deploy
[4]: https://github.com/bloomper/fgv-spexflix-spec
[5]: https://www.apache.org/licenses/LICENSE-2.0.html
