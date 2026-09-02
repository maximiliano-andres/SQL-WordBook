import { describe, it, expect } from 'vitest';
import { sanitizeForSpreadsheet } from './csv';

describe('sanitizeForSpreadsheet', () => {
  it('no modifica un valor normal', () => {
    expect(sanitizeForSpreadsheet('Juan Pérez')).toBe('Juan Pérez');
    expect(sanitizeForSpreadsheet('123')).toBe('123');
  });

  it.each(['=', '+', '-', '@'])('antepone un apóstrofe a valores que empiezan con "%s"', (prefix) => {
    expect(sanitizeForSpreadsheet(`${prefix}SUM(A1:A2)`)).toBe(`'${prefix}SUM(A1:A2)`);
  });

  it('antepone un apóstrofe a valores que empiezan con tab o retorno de carro', () => {
    expect(sanitizeForSpreadsheet('\tvalor')).toBe("'\tvalor");
    expect(sanitizeForSpreadsheet('\rvalor')).toBe("'\rvalor");
  });

  it('retorna un string vacío tal cual sin lanzar error', () => {
    expect(sanitizeForSpreadsheet('')).toBe('');
  });

  it('no lanza error con valor null y no le agrega prefijo (no empieza con carácter riesgoso)', () => {
    expect(() => sanitizeForSpreadsheet(null)).not.toThrow();
    expect(sanitizeForSpreadsheet(null)).toBe('null');
  });
});
