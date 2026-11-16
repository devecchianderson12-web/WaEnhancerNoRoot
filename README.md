# WaEnhancer Sin Root

Recreación de [WaEnhancer](https://github.com/Dev4Mod/WaEnhancer) que funciona **sin root y sin LSPosed**.
La app trae todas las funciones del módulo original y, además, un **modo sin root** que parchea
WhatsApp en el propio celular: le integra el módulo y el motor de hooks (LSPlant, el mismo que usa
LSPosed/Vector), así cada función corre dentro de WhatsApp igual que si tuvieras root.

> ⚠️ Proyecto educativo. Modificar WhatsApp puede violar sus términos y hay riesgo de bloqueo de la cuenta.
> Usalo bajo tu responsabilidad. Este repositorio **no distribuye APKs de WhatsApp**: el parche se hace en
> tu celular con la copia de WhatsApp que ya tenés.

## Descarga

En [Releases](../../releases/latest) siempre está la última compilación:

| Archivo | Para |
| --- | --- |
| `WaEnhancerNoRoot-V<n>.apk` | WhatsApp |
| `WaEnhancerNoRoot-Business-V<n>.apk` | WhatsApp Business |

## Modo sin parche (WhatsApp oficial, sin reinstalar)

Si no querés tocar WhatsApp, en **Inicio → Sin parche · WhatsApp oficial** hay funciones que trabajan
junto al WhatsApp de Play Store, que se sigue actualizando normal:

- **Leer sin tildes azules**: los mensajes se guardan desde las notificaciones y se leen en esta app.
- **Mensajes eliminados y editados**: si alguien borra o edita un mensaje, queda la copia y te avisa.
- **Guardar estados**: los estados que WhatsApp ya descargó se copian a la galería.

Solo pide **acceso a notificaciones** y **acceso a archivos** (para los estados). Ve únicamente lo que
WhatsApp muestra en la notificación: no funciona con chats silenciados ni con el chat abierto, y de las
fotos o audios guarda solo la descripción. Ocultar «en línea», ver una vez y el resto de las funciones
necesitan el modo con parche (abajo) o root, porque Android no deja meter código en otra app sin modificarla.

## Cómo usarlo (sin root)

1. Instalá el APK y abrilo.
2. En **Inicio** tocá **Activar sin root → Abrir**.
3. Tocá **Parchear e instalar** (usa el WhatsApp instalado) o **Elegir APK** (APK, APKS, XAPK o APKM).
4. La primera vez Android pide reinstalar WhatsApp porque la firma cambia:
   1. En WhatsApp: *Ajustes → Chats → Copia de seguridad* y hacé una copia.
   2. Tocá **Desinstalar**; al volver, la versión parcheada se instala sola.
   3. Abrí WhatsApp y restaurá la copia.
5. Configurá todo desde esta app, igual que con LSPosed. Los cambios se aplican al reiniciar WhatsApp.

Cuando se actualice esta app o quieras otra versión de WhatsApp, volvé a **Parchear** (ya no hace falta
desinstalar ni se pierden chats, porque la firma es la misma).

### ¿Root/LSPosed?

Si tenés LSPosed o Vector, el APK sigue funcionando como módulo normal.

## Cómo funciona

| Con root (LSPosed) | Sin root (esta app) |
| --- | --- |
| Zygisk inyecta el framework en todos los procesos | El framework se integra dentro del APK de WhatsApp (`appComponentFactory` del loader de LSPatch) |
| El módulo se carga desde la app instalada | El módulo se embebe en `assets/lspatch/modules/` del WhatsApp parcheado |
| Un hook en `system_server` le da visibilidad a WhatsApp sobre el módulo | Se agrega `QUERY_ALL_PACKAGES` al manifest de WhatsApp |
| La firma original queda intacta | Bypass de firma nivel 2 (PackageManager + `openat`): WhatsApp ve su firma y su APK originales |
| Ajustes por LibXposed remote preferences | Ajustes por el `ContentProvider` de la app (el módulo ya los soporta) |

El parcheo usa el motor oficial de [LSPatch (fork de JingMatrix)](https://github.com/JingMatrix/LSPatch):
el workflow descarga `lspatch.jar`, lo convierte a dex con `d8` (`scripts/build-engine.sh`) y la app lo
ejecuta en un `DexClassLoader` aislado. El APK resultante se firma con la clave por defecto de LSPatch y se
instala con `PackageInstaller`.

Lo único que no se puede sin root son los hooks del sistema (`system_server`): la protección contra
*downgrade* y el bypass de visibilidad de paquetes, que se reemplazó por el permiso de arriba.

## Compilar

Cada push a `main` ejecuta `.github/workflows/build.yml`, que:

1. arma el motor de parcheo (`scripts/build-engine.sh`),
2. compila las variantes WhatsApp y Business,
3. reemplaza el release `latest` con `WaEnhancerNoRoot-V<n>.apk` (n = número de ejecución).

Para que las actualizaciones se instalen encima, cargá en *Settings → Secrets* `KEY_STORE` (keystore en
base64), `KEY_STORE_PASSWORD`, `ALIAS` y `KEY_PASSWORD`.

## Créditos

- [WaEnhancer](https://github.com/Dev4Mod/WaEnhancer) — Dev4Mod y colaboradores (módulo y funciones)
- [LSPatch](https://github.com/JingMatrix/LSPatch) / [Vector](https://github.com/JingMatrix/Vector) — JingMatrix (framework sin root)
- [LSPlant](https://github.com/JingMatrix/LSPlant) — motor de hooks ART
- [REA](https://github.com/morluto/rea) — metodología de análisis de APKs usada para estudiar el módulo

Licencia GPL-3.0, igual que el proyecto original. La documentación original está en [docs/](docs/README.md).
