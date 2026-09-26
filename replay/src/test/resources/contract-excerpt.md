# Telemetry contract (excerpt for tests)

| Field | Type | Meaning |
| --- | --- | --- |
| `seq` | int64 | not a signal: before the appendix, so ignored |

## Appendix: the current signal catalogue

| Signal | Quantity | Unit | Value |
| --- | --- | --- | --- |
| `diagnostics.mil` | None | — | state / flag / flags |
| `engine.coolant_temperature` | Temperature | °C | number |
| `engine.rpm` | AngularSpeed | rpm | number |
| `vehicle.speed` | Speed | km/h | number |
