# Multi-Arch-Basis mit Babashka (amd64 + arm64 im selben Tag).
FROM babashka/babashka:latest

WORKDIR /app
COPY run.clj ./run.clj
COPY web/ ./web/

# Entrypoint bewusst setzen, damit es unabhaengig vom Basis-Image ist:
ENTRYPOINT ["bb"]
CMD ["run.clj"]
