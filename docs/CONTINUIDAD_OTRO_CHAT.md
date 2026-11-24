# SlipTrain — continuidad para otro chat

**Actualizado el 24 de noviembre de 2026 · Europe/Madrid.** SlipTrain es una app Android nativa en Java que avisa antes de llegar a una parada de Metro o Cercanías de Madrid. El nombre visible y el proyecto Gradle son SlipTrain. El ID de aplicación sigue siendo `es.yori.sleeptrain` para actualizar la instalación existente. Versión `0.5.0` (código `5`).

## Texto para abrir el siguiente chat

> Continúa SlipTrain en `/home/yori/Documentos/ChatGPT/sleep_Train`. Lee `docs/CONTINUIDAD_OTRO_CHAT.md` y consulta el código para el comportamiento vigente. «Viaje ahora» arranca directamente con un destino y tiene selector de rutas opcional. El seguimiento puede recalcular la ruta si el GPS confirma una desviación. Las rutinas guardan ida y vuelta por separado. La validación física sigue pendiente. La integración de autobuses sigue en fase de propuesta en `docs/PLAN_AUTOBUSES.md`.

## Flujo vigente

- **Viaje ahora.** Selecciona una parada y pulsa **Iniciar viaje**. Si falta una ubicación válida, el servicio prepara el aviso y espera en segundo plano. **Elegir ruta** es opcional. La elección se usa como ruta inicial y se confirma por separado del inicio del viaje.
- **Alternativas.** **Filtrar** muestra todos los operadores, solo Metro o solo Cercanías. **Ordenar** alterna entre recomendadas, menor tiempo de viaje y menos transbordos. Cada sentido de una rutina tiene sus propios controles en el selector. **Más rutas** amplía la lista de recomendaciones. Cercanías puede incorporar esperas, retrasos y alertas en el selector. Metro usa los tiempos del catálogo.
- **Recálculo.** `RouteCorrection` busca una salida alternativa cuando una muestra no encaja con el tramo esperado. Necesita ubicación reciente con precisión de 25 m o mejor, al menos 12 s y 220 m entre muestras, y avance compatible con una sola alternativa. Conserva el destino, la antelación, las preferencias de transbordo y el audio. Se usa con rutas elegidas o automáticas, también tras la espera de GPS o de embarque. Las rutas guardadas del perfil siguen sirviendo de punto de partida para futuros viajes.
- **Límites del GPS.** Se conserva el último trayecto confirmado mientras falte señal o las rutas sean ambiguas. Los cruces, andenes compartidos y túneles pueden retrasar la detección. Si una incidencia o un transbordo ya ha disparado un aviso, el usuario debe atenderlo.
- **Transbordos.** Usan **Ajustes → Antelación del aviso** cuando está marcada **Avisarme en los transbordos**. Si se confirma un aviso temprano, el seguimiento continúa hasta el intercambiador. Sin avisos de transbordo, el sistema pasa a detectar el siguiente tren automáticamente. **Ya estoy en el siguiente tren** permite avanzar manualmente.
- **Rutinas.** Guardan ruta y horario de ida y vuelta por separado, con días, etiqueta, audio, vibración y preferencia de avisos. Las rutinas necesitan permisos de ubicación precisa y en segundo plano, notificaciones y alarmas exactas cuando Android lo exige. La hora abre una ventana de detección de hasta 30 min. Se prioriza la salida guardada y se admite una alternativa clara desde el origen.
- **Audio y aspecto.** El sonido por altavoz y auriculares tiene interruptores independientes. El selector acepta archivos locales y conserva una copia privada. Siete toques en el título **Audio de la alarma** desbloquean el clip de Keyboard Cat empaquetado como AAC y reproducen una prueba de cinco segundos. El progreso puede ser horizontal o vertical. La interfaz usa transiciones de 150 ms y controles con flechas centradas.

## Código

| Componente | Responsabilidad |
| --- | --- |
| `MainActivity`, `RouteProgressView`, `ViewMotion` | Pantallas, inicio directo, selector opcional, filtros, ajustes y progreso. |
| `AutomaticRoutePlanner`, `JourneyPlan`, `RouteEstimates` | Grafo de estaciones, rutas y transbordos, claves persistentes, comparación por horarios, filtros y orden. |
| `SessionStore`, `TripService`, `RouteCorrection`, `JourneyEngine` | Estado persistente, espera de origen o ubicación, seguimiento y cambio de itinerario con un token nuevo. |
| `CommuteDetection`, `MovementEvidence`, `CommuteScheduler` | Arranque de rutinas, detección de movimiento y programación. |
| `AlarmScheduler`, `AlarmService`, `AlarmPlayer`, `AlarmAudio` | Respaldo exacto, reproducción, enrutado de audio, importación y tono secreto. |
| `TransitRepository`, `data/realtime/`, `scripts/` | Catálogo y datos en tiempo real por operador. |

La sesión usa `BOOT_COUNT` y reloj monotónico. Reiniciar el teléfono descarta el viaje en curso y reprograma las rutinas. Los tokens invalidan los avisos anteriores tras un cambio de itinerario o tramo. El GPS reciente tiene prioridad durante 30 s. Después se puede usar tiempo real fiable de Renfe. El respaldo nominal se ancla a la última observación útil y aplica un margen de 15 s en los avisos.

## Compilación y pruebas

Java 17 como nivel de compatibilidad, AGP 9.4.1, Gradle 9.6.0, SDK 36 y mínimo 26. En este equipo se usa el JBR de Android Studio y la caché local de Gradle. El APK de depuración está en `app/build/outputs/apk/debug/app-debug.apk`. La firma de depuración debe coincidir con la anterior para instalar encima.

**Verificado el 24/11/2026.** Pasaron 135 pruebas Java/Android y 6 de catálogo. Lint sin errores, con dos avisos de SDK disponible. APK de 2.734.826 bytes y firma de depuración verificada.

En emulador API 37, el inicio directo esperó el GPS con la pantalla apagada y arrancó el seguimiento al recuperarlo. También se probaron la selección opcional, los filtros y el orden de rutas. Una ruta elegida de Metro hacia Móstoles Central se recalculó a C5 al simular ubicaciones en Aluche y Maestra Justa Freire, con el mismo destino y un token nuevo.

```text
APK SHA-256: b217a8dccb79de1cdbe4040b45f7f82c6bba81592992e1a56ad022c83e3b63fa
Certificado SHA-256: 4a7616173e18d26c0c04dd5d25171c159535f16ae98e3cffabf81fde73c4b712
```

```bash
cd /home/yori/Documentos/ChatGPT/sleep_Train
JAVA_HOME=/home/yori/.local/opt/android-studio-f8775c67cf89/jbr \
ANDROID_HOME=/home/yori/Android/Sdk \
  ./gradlew --offline --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

Casos de regresión útiles:

- **Alfonso XIII → Móstoles Central.** Iniciar directamente, esperar el GPS con pantalla apagada e iniciar al recuperar señal. Elegir una alternativa y simular un desvío antes o después de un transbordo.
- **Móstoles Central → Alfonso XIII.** El catálogo ofrece C5 → L3 → L4 por Embajadores y Argüelles, y C5 → L3 → L2 → L4 por Embajadores, Sol y Goya.
- **Atocha → Móstoles-El Soto en C5.** Con una parada de antelación, el aviso va en Móstoles. Móstoles Central y Móstoles-El Soto son destinos distintos.

**Pendiente en móvil real** medir la antelación con pantalla apagada, verificar desvíos y recuperación de GPS en túneles y probar Bluetooth, desconexión de auriculares y No molestar.

## Referencias y mantenimiento

- [README](../README.md) contiene instrucciones de uso y compilación. [Maqueta revisada](sleeptrain-wireframe-revision.html) y [Figma](https://www.figma.com/design/U4nXXLgb3xMuhLklgfaHLR?node-id=3-2663) son referencias visuales; el código Android contiene el comportamiento vigente.
- [PLAN_AUTOBUSES.md](PLAN_AUTOBUSES.md) desarrolla la propuesta de EMT e interurbanos. La compilación actual cubre Metro y Cercanías.
- El catálogo empaquetado se generó el 20/11/2026. Revisar la renovación del GTFS de Renfe cerca del 19/12/2026. Los calendarios antiguos de Metro se usan como topología y referencia de duración. Revisar [seguimiento automático](AUTOMATIC_TRACKING.md) y [catálogo](../app/src/main/assets/transit_catalog.json).
- Git excluye compilaciones, `dist/`, configuración de máquina y keystores.
- Repositorio privado del proyecto en GitHub. [Yori-Martinez/SlipTrain](https://github.com/Yori-Martinez/SlipTrain)
