import { describe, it, expect } from 'vitest';
import { fkStatusText } from './fk';

describe('fkStatusText', () => {
  it('retorna null cuando la resolución es null', () => {
    expect(fkStatusText(null)).toBeNull();
  });

  it('retorna null cuando la resolución es undefined', () => {
    expect(fkStatusText(undefined)).toBeNull();
  });

  it('retorna el valor real cuando el status es RESOLVED', () => {
    expect(fkStatusText({ status: 'RESOLVED', value: 'Juan Pérez' })).toBe('Juan Pérez');
  });

  it('retorna null cuando el status es NULL', () => {
    expect(fkStatusText({ status: 'NULL' })).toBeNull();
  });

  it('retorna "No encontrado" cuando el status es ORPHAN', () => {
    expect(fkStatusText({ status: 'ORPHAN' })).toBe('No encontrado');
  });
});
