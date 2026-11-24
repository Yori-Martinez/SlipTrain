# Validación de SleepTrain 0.5

El 20 de noviembre de 2026 se completaron **119 pruebas Java y Android** y **6 pruebas Python**. Lint terminó con 0 errores y 16 advertencias. El APK de depuración conserva el identificador y el certificado de la versión 0.4, por lo que puede instalarse encima. La compilación incluye el catálogo ferroviario y sus índices de viajes. Los detalles de compilación y firma están en `dist/validation-0.5.json`.

Las pruebas nuevas cubren el itinerario Alfonso XIII → Móstoles Central y su regreso, destinos con varios operadores, transbordos, editor unificado, conservación del audio durante un cambio de tramo, audio importado, repetición semanal, cambios de hora, interruptores, restauración de la programación y detección basada en movimiento. El reloj de una rutina únicamente abre una ventana de detección. Para iniciar el viaje, la secuencia de ubicaciones tiene que ser compatible con una de sus salidas.

En el emulador API 37 se guardó la rutina Alfonso XIII ↔ Móstoles Central a las 07:00 y 14:00 para lunes, miércoles y viernes. Se importó un WAV con el selector de Android, se comprobó que la etiqueta vacía muestra el destino, se apagó la rutina con el interruptor y se verificaron los dos eventos de alarma exacta en Android.

Con GPS simulado, la app calculó el viaje puntual introduciendo únicamente Móstoles Central y detectó Alfonso XIII como origen. También se adelantó el reloj del emulador hasta cinco segundos antes de las 07:00 con la app en segundo plano. Android abrió la detección y, al recibir posiciones en Alfonso XIII y Prosperidad, se inició un viaje activo con la rutina y su archivo de audio.

Queda pendiente repetir el flujo en un teléfono y un viaje real, especialmente la detección con pantalla apagada en túneles y la salida por Bluetooth.

---

## Validación histórica de SleepTrain 0.4

Validación del 18 de noviembre de 2026: **99 pruebas Java/Android y cinco Python**, sin fallos, errores ni casos omitidos. `assembleDebug` y `lintDebug` correctos; las advertencias actuales se registran en `dist/validation-0.4.json`.

Se mantienen las pruebas de 0.3 y se añaden 14 de `AutoTrainMatcher`: coordenadas, estaciones sin coordenadas, secuencias, ambigüedad, marcas repetidas, ausencia de movimiento, sentido/ruta/fecha/servicio parcial incorrectos, GPS y feed antiguos, fecha omitida, pérdida de señal, ID desconocido, contradicción y enlace con previsiones e incidencias. Dos pruebas Android adicionales comprueban la normalización de ajustes antiguos y la persistencia/liberación del ID. Una prueba del motor verifica que una incidencia crítica no sustituya un GPS reciente por una alarma prematura.

Distribución: `JourneyEngineTest` 28, `StationMatcherTest` 10, `SessionFlowTest` 16, `AutoTrainMatcherTest` 14; las otras cinco suites suman 31 casos. Las comprobaciones de pantalla incluyen ausencia de selectores de tren/GPS/modo de salida, texto «Automático», navegación Metro/Cercanías/Transbordo y datos de precisión durante el viaje.

APK: `dist/SleepTrain-0.4.0-debug.apk`. Metadatos, firma y comprobaciones de interfaz: `dist/validation-0.4.json` y `dist/qa/`. No se ha demostrado la asociación automática en un viaje físico: trenes muy próximos o datos incompletos pueden impedir identificar un ID y se mantiene el GPS con el recorrido local. Véase [AUTOMATIC_TRACKING.md](AUTOMATIC_TRACKING.md).

---

## Validación histórica de SleepTrain 0.3

Actualización del 18 de noviembre de 2026: **82 pruebas Java/Android y cinco Python**, sin fallos, errores ni casos omitidos. `assembleDebug` y `lintDebug` correctos: cero errores y 16 advertencias de Lint (versiones y textos en la interfaz). Las versiones de dependencias y SDK se han conservado.

La regresión nueva cubre señal breve en estación, corrección hacia atrás, espera en origen, rechazo de duplicados/lecturas antiguas/saltos/ambigüedad, proyección entre estaciones, prioridad del GPS ante datos contradictorios, caducidad, pérdida de señal, calibración del tiempo, reconstrucción de sesión, respaldo programado antes de corregirse el GPS, limpieza en transbordos y reanudación al abrir un viaje activo.

Distribución: `JourneyEngineTest` 27, `StationMatcherTest` 10, `SessionFlowTest` 14; las otras cinco suites suman 31 casos. La suite Python conserva cinco comprobaciones del catálogo.

La validación de emulador y artefactos se registra en `dist/validation-0.3.json`; las capturas están en `dist/qa/`. El APK distribuido es `dist/SleepTrain-0.3.0-debug.apk`. Los informes de Gradle en `app/build/reports/` se regeneran al ejecutar la suite y corresponden a la versión actual, no a la histórica que se conserva debajo.

El usuario ha realizado una prueba física de 0.2 y ha comunicado un aviso dos o tres paradas antes. No se dispone de un registro de ese trayecto. La 0.3 necesita repetir supervisadamente C5, L3, transbordos, pantalla apagada, pérdida/recuperación de señal y audio en el teléfono. Las pruebas automáticas y el emulador no demuestran fiabilidad en un túnel real. Véase [GPS_CORRECTION.md](GPS_CORRECTION.md).

---

## Validación histórica de SleepTrain 0.2

Validación del 16 de noviembre de 2026: **62 pruebas de Java/Android y cinco de Python superadas**, sin fallos ni pruebas omitidas. `assembleDebug` y `lintDebug` terminan correctamente: cero errores y 14 advertencias de Lint. El APK tiene firma verificada y conserva el certificado de la versión 0.1, por lo que puede actualizarla.

Consulta real del 16/11/2026 a las 12:29:59, hora de Madrid: los tres feeds de Renfe eran recientes; se enlazaron **21 viajes de C5 por ID exacto** y se confirmó una observación de vehículo contra su viaje estático. Es una comprobación puntual, no una garantía de disponibilidad futura. La prueba de teleindicadores L3 se recoge por separado en `METRO_DATA_RESEARCH.md`.

## Comandos

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

Diagnóstico opcional de los datos actuales de Renfe:

```bash
./gradlew :app:probeFeeds
```

`probeFeeds` descarga y decodifica los tres feeds binarios, comprueba su antigüedad y cruza los `trip_id` actuales con el catálogo de C5 en memoria. Puede fallar legítimamente si no hay servicio, el servidor no actualiza o el catálogo está fuera de vigencia; no forma parte de la suite sin red.

Informes: `app/build/reports/tests/testDebugUnitTest/` y `app/build/reports/lint-results-debug.html`. APK: `app/build/outputs/apk/debug/app-debug.apk`. La distribución local contiene `dist/SleepTrain-0.2.0-debug.apk` y `dist/validation-0.2.json`, con firma y SHA-256 comprobados al prepararla.

## Cobertura automática

- Motor: aviso por estación y por tiempo, repetición/retroceso/salto de estaciones, restauración, cambios de hora, prioridad y caducidad de previsiones, y llegada confirmada que prevalece sobre un horario posterior.
- Localización opcional: precisión, antigüedad, lecturas distintas y corroboración temporal. No se necesita ubicación para las pruebas del seguimiento GTFS-RT.
- GTFS: CSV con Unicode, comillas, comas y saltos de línea; relleno de Renfe, calendario y excepciones, horas posteriores a medianoche, ambos sentidos, servicios parciales y rechazo de datos incompletos.
- Planificación: C5 completa y L3, viaje habitual, ambos órdenes del transbordo, elección de intercambiador, tramos cortos y omisión de tramos sin movimiento. No se inventa un viaje largo entre dos paradas del mismo intercambiador.
- GTFS-RT: protobuf real y sintético, cruce exacto de ID y fecha, `stop_sequence`, datos antiguos/futuros, snapshot completo y borrados diferenciales, retrasos, cancelaciones, paradas omitidas, `NO_DATA` y selectores/periodos de incidencias.
- Metro: validación de línea, estación, sentido, fecha y nulos del panel; la llegada al panel nunca se interpreta como posición del pasajero.
- Android simulado con Robolectric, API 35: creación de viaje sin GPS, armado del respaldo, transición a espera de transbordo, confirmación del siguiente tren, token renovado, rechazo de alarmas antiguas, cancelación final y reinicio. También se comprueba la navegación entre las tres opciones de transporte.
- Python: integridad del catálogo empaquetado y representación explícita de la L3 sin viajes en un GTFS vencido.

El entorno Android simulado comprueba lógica e integración; no valida el sonido físico, el consumo real ni el comportamiento específico del fabricante.

## Pruebas físicas y de trayecto pendientes

| Caso | Resultado exigido |
| --- | --- |
| Android 8 y Android 16, pantalla estrecha y letra grande | Instalación, lectura y controles accesibles sin fallos. |
| Cercanías sin permiso ni servicio de ubicación | Sigue consultando Renfe y arma el respaldo. |
| Tren asociado correctamente y otro de la misma línea | Solo el ID asociado modifica posición y previsión. |
| No hay internet o los datos dejan de actualizarse | Se señala la pérdida de datos recientes y se aplica el respaldo, incluso con la pantalla apagada. |
| Corte de servicio, cancelación o parada de destino suprimida | Muestra el motivo y despierta al usuario afectado. |
| Metro L3 sin GPS | Identifica claramente la estimación; los paneles de otros trenes no confirman el progreso del pasajero. |
| Metro → C5 y C5 → Metro por ambos intercambiadores | Un aviso por tramo; espera y confirmación del segundo embarque. |
| Reconocer el primer aviso antes del transbordo | No comienza el siguiente tramo durante la caminata. |
| Cancelar y preparar otro viaje | No se ejecutan alarmas ni resultados del viaje cancelado. |
| Pantalla apagada y Doze | El respaldo suena y se puede detener desde la notificación. |
| Auriculares con cable, USB y Bluetooth; desconexión y llamada | Salida solicitada o respaldo configurado; sonido y vibración se detienen al reconocer. |
| Volumen cero, No molestar y restricciones de batería | Registrar comportamiento real y comprobar que el usuario puede conocer la limitación. |
| Reinicio, detención forzada o permiso de alarma retirado | No se presenta el viaje como protegido; la interrupción queda explicada. |

Antes de confiar en la alarma, realizar viajes supervisados, permaneciendo despierto, y anotar estaciones e instantes reales de cada aviso. Separar avisos por posición, previsión y estimación. Registrar el móvil, Android, conexión, tren asociado, salida de audio, antelación y batería. Un aviso muy temprano no cuenta como localización correcta. Las duraciones provisionales de L3 y del cambio de andén deben calibrarse o sustituirse por datos oficiales vigentes.
