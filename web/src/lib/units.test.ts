import { describe, expect, it } from 'vitest'
import { fromShown, shownUnit, toShown } from './units'

describe('units', () => {
  it('shows metric as sent', () => {
    expect(shownUnit('km/h', 'metric')).toBe('km/h')
    expect(toShown(100, 'km/h', 'metric')).toBe(100)
  })
  it('converts to US where there is a counterpart', () => {
    expect(shownUnit('km/h', 'us')).toBe('mph')
    expect(toShown(100, 'km/h', 'us')).toBeCloseTo(62.1371, 4)
    expect(toShown(100, '°C', 'us')).toBeCloseTo(212, 6)
    expect(toShown(-40, '°C', 'us')).toBeCloseTo(-40, 6)
    expect(toShown(100, 'kPa', 'us')).toBeCloseTo(14.50377, 4)
    expect(toShown(10, 'km', 'us')).toBeCloseTo(6.21371, 4)
    expect(toShown(10, 'L/h', 'us')).toBeCloseTo(2.641721, 5)
    expect(toShown(100, 'm', 'us')).toBeCloseTo(328.084, 3)
    expect(shownUnit('°C', 'us')).toBe('°F')
    expect(shownUnit('m', 'us')).toBe('ft')
  })
  it('leaves units with no US counterpart alone', () => {
    for (const unit of ['rpm', '%', 'V', 'g/s', 'mA', 's', '°', '']) {
      expect(shownUnit(unit, 'us')).toBe(unit)
      expect(toShown(42, unit, 'us')).toBe(42)
    }
  })
  it('converts back exactly, for ranges and zones', () => {
    for (const unit of ['km/h', 'km', '°C', 'kPa', 'L/h', 'm', 'rpm']) {
      expect(fromShown(toShown(105, unit, 'us'), unit, 'us')).toBeCloseTo(105, 9)
    }
  })
})
