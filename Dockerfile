# Imagen del API. Dos etapas: compilar y, aparte, la que se despliega.
#
# La imagen final no lleva sistema operativo. Puede permitírselo porque este
# binario no necesita nada de debajo:
#
#   - No usa cgo, así que enlaza estático (CGO_ENABLED=0).
#   - No escribe en disco: las migraciones, el léxico y el corpus del RAG van
#     embebidos con go:embed.
#   - Lleva su propia base de zonas horarias (ver el import de time/tzdata).
#
# Lo único que sí necesita del entorno son los certificados raíz, para hablar
# con Gemini por HTTPS; distroless/static los trae.

FROM golang:1.26-alpine AS build

WORKDIR /src

# Las dependencias en una capa aparte: mientras go.mod y go.sum no cambien,
# Docker reutiliza la descarga y una recompilación solo cuesta el código.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# -trimpath quita las rutas de compilación del binario; -s -w quita la tabla de
# símbolos y la información de depuración. Entre los dos ahorran varios MB.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /api ./cmd/api


# Si en algún momento necesitas depurar dentro del contenedor, cambia esta línea
# por `FROM alpine:3.20` y añade `RUN apk add --no-cache ca-certificates`: te da
# una shell a cambio de más superficie de ataque.
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /api /api

# Sin root dentro del contenedor. La imagen ya trae el usuario creado.
USER nonroot:nonroot

EXPOSE 8080
ENTRYPOINT ["/api"]
