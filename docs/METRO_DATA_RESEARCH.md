# Búsqueda de datos en tiempo real de Metro de Madrid

Comprobación: 16 de noviembre de 2026. No se ha localizado una URL pública operativa de GTFS-RT de Metro de Madrid. Esto no demuestra que no exista un feed privado o un acceso acordado con el operador.

## Fuentes estáticas comprobadas

El [GTFS Red de Metro del CRTM](https://datos.crtm.es/datasets/5c7f2951962540d69ffe8f640d94c246/about) se describe oficialmente como [oferta planificada: horarios y topología](https://datos.gob.es/es/catalogo/a13002908-oferta-planificada-de-la-red-de-metro1). El ZIP descargado desde ArcGIS contiene calendarios hasta el 27 de mayo de 2026 y no incluye viajes L3 en `trips.txt`, aunque sí la ruta y sus estaciones. No se han extendido ficticiamente sus fechas de validez.

La [ficha actual de la L3](https://www.crtm.es/tu-transporte-publico/metro/lineas/4__3___) confirma El Casar–Moncloa y las correspondencias con la C5 en Embajadores y Villaverde Alto. Se usa para completar el recorrido de referencia. El punto de El Casar del ZIP corresponde al intercambiador histórico; no se afirma que sea un ID de andén de la L3 contrastado con un feed de viajes actual.

Los catálogos de feeds consultados y la ficha histórica del NAP no han aportado una URL de `VehiclePositions`, `TripUpdates` o `ServiceAlerts` GTFS-RT para Metro de Madrid. Las referencias a GTFS-RT de Renfe y EMT no son feeds de Metro.

## Alternativa de llegadas comprobada

Existe un servicio REST JSON en el dominio de Metro, usado por clientes de su aplicación:

`https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/{codigoEmpresa}`

Pruebas HTTPS sin credenciales a las 12:04, hora de Madrid:

| Código | Resultado observado |
| --- | --- |
| [0304](https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/0304) | HTTP 200; Embajadores, L3, ambos andenes; previsiones recientes. |
| [0357](https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/0357) | HTTP 200; Villaverde Alto, L3; algunos registros recientes y otros antiguos. |
| 0306 | HTTP 200; lista vacía en la consulta. |
| 0311 | HTTP 200; datos de L6 en Moncloa; no sirven para la L3 y deben filtrarse. |
| 46 y 48 | HTTP 200; listas vacías. Los números de estación GTFS no son códigos de empresa intercambiables. |

La respuesta contiene `linea`, `nombreest`, `anden`, `sentido`, `proximo`, `siguiente` y `fechaHoraEmisionPrevision`. Los valores de llegada se expresan en minutos desde la emisión y pueden ser nulos. No contiene un identificador de tren que permita enlazar de forma inequívoca la misma circulación entre estaciones.

La ruta y el mapeo inicial se localizaron en una [implementación pública de cliente](https://github.com/dieguezz/mcp-madrid-public-transport/blob/main/src/transport/metro/infrastructure/metro-api-client.ts) y se comprobaron contra el servidor del operador. No se ha localizado documentación pública de Metro que establezca una API GTFS-RT, estabilidad, cuota o contrato de reutilización para este endpoint. Que responda sin autenticación no equivale a tener ese contrato.

## Consecuencia para SleepTrain

Renfe puede usar sus tres feeds GTFS-RT en el móvil. Metro puede mostrar las llegadas verificadas como contexto, con filtros de línea, estación, sentido y antigüedad. Un contador del próximo tren en una estación no confirma la posición del pasajero ni identifica el tren abordado. La alarma L3 continúa por estimación local y confirmaciones manuales o ubicación opcional mientras no se disponga de una fuente identificable de seguimiento.
