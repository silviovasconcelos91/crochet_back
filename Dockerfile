# Packages the native binary built by `./mvnw -Pnative native:compile` (Linux amd64).
# java-base: distroless glibc base plus the zlib and OpenSSL libraries the native image links (no JVM)
FROM gcr.io/distroless/java-base-debian12:nonroot
COPY --chmod=755 target/crochet_back /app/crochet_back
EXPOSE 8080
ENTRYPOINT ["/app/crochet_back"]
