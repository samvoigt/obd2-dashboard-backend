import { describe, expect, it } from 'vitest'
import { readout } from './readout'

describe('readouts', () => {
  it('write whole numbers, or one decimal where it matters, in the viewer’s units', () => {
    expect(readout(1816.5, 'rpm', 'metric')).toEqual({ text: '1817', unit: 'rpm' })
    expect(readout(14.26, 'V', 'metric')).toEqual({ text: '14.3', unit: 'V' })
    expect(readout(100, 'km/h', 'us')).toEqual({ text: '62', unit: 'mph' })
    expect(readout(3.2, 'L/h', 'us')).toEqual({ text: '0.8', unit: 'gal/h' })
    expect(readout(null, '°C', 'us')).toEqual({ text: '—', unit: '°F' })
    expect(readout(Number.NaN, 'rpm', 'metric').text).toBe('—')
  })
})
