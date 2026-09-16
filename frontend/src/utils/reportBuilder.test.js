import { describe, it, expect } from 'vitest';
import { pickDescriptiveColumn, getNextAlias, buildQueryPayload } from './reportBuilder';

describe('pickDescriptiveColumn', () => {
  it('retorna null para una lista vacía', () => {
    expect(pickDescriptiveColumn([])).toBeNull();
    expect(pickDescriptiveColumn(null)).toBeNull();
  });

  it('prefiere una columna con nombre descriptivo (razon_social) sobre id', () => {
    const cols = [{ name: 'id' }, { name: 'razon_social' }, { name: 'creado_en' }];
    expect(pickDescriptiveColumn(cols).name).toBe('razon_social');
  });

  it('cae a la primera columna que no empiece con "id" si no hay coincidencia por nombre', () => {
    const cols = [{ name: 'id' }, { name: 'codigo' }, { name: 'activo' }];
    expect(pickDescriptiveColumn(cols).name).toBe('codigo');
  });

  it('cae a la primera columna si todas empiezan con "id"', () => {
    const cols = [{ name: 'id' }, { name: 'id_secundario' }];
    expect(pickDescriptiveColumn(cols).name).toBe('id');
  });
});

describe('getNextAlias', () => {
  it('retorna t1 cuando no hay joins', () => {
    expect(getNextAlias([])).toBe('t1');
    expect(getNextAlias(undefined)).toBe('t1');
  });

  it('continúa la numeración a partir del alias más alto existente', () => {
    const joins = [{ table: { alias: 't1' } }, { table: { alias: 't3' } }];
    expect(getNextAlias(joins)).toBe('t4');
  });

  it('ignora joins sin tabla o alias asignado', () => {
    const joins = [{ table: { alias: 't1' } }, {}, { table: {} }];
    expect(getNextAlias(joins)).toBe('t2');
  });
});

describe('buildQueryPayload', () => {
  const baseTable = { schema: 'dbo', name: 'Facturas', alias: 't0' };

  it('retorna null sin tabla base', () => {
    expect(buildQueryPayload({ baseTable: null, page: 1, limit: 15 })).toBeNull();
  });

  it('calcula el offset a partir de page y limit', () => {
    const payload = buildQueryPayload({ baseTable, joins: [], selectedColumns: [], filters: [], sorts: [], page: 3, limit: 15, distinct: false });
    expect(payload.offset).toBe(30);
    expect(payload.limit).toBe(15);
  });

  it('descarta joins incompletos (sin columna de cruce en algún lado)', () => {
    const joins = [
      { type: 'LEFT', table: { schema: 'dbo', name: 'Clientes', alias: 't1' }, onLeft: { tableAlias: 't0', column: 'clienteId' }, onRight: { tableAlias: 't1', column: 'id' } },
      { type: 'LEFT', table: { schema: 'dbo', name: 'Incompleto', alias: 't2' }, onLeft: { tableAlias: 't0', column: '' }, onRight: { tableAlias: 't2', column: 'id' } }
    ];
    const payload = buildQueryPayload({ baseTable, joins, selectedColumns: [], filters: [], sorts: [], page: 1, limit: 15, distinct: false });
    expect(payload.joins).toHaveLength(1);
    expect(payload.joins[0].table.alias).toBe('t1');
  });

  it('descarta filtros sin valor salvo IS NULL / IS NOT NULL', () => {
    const filters = [
      { tableAlias: 't0', column: 'monto', operator: '=', value: '' },
      { tableAlias: 't0', column: 'clienteId', operator: 'IS NULL', value: '' },
      { tableAlias: 't0', column: 'estado', operator: '=', value: 'activo' }
    ];
    const payload = buildQueryPayload({ baseTable, joins: [], selectedColumns: [], filters, sorts: [], page: 1, limit: 15, distinct: false });
    expect(payload.filters).toHaveLength(2);
    expect(payload.filters.map((f) => f.column)).toEqual(['clienteId', 'estado']);
  });

  it('descarta ordenamientos sin columna', () => {
    const sorts = [{ tableAlias: 't0', column: '', direction: 'ASC' }, { tableAlias: 't0', column: 'fecha', direction: 'DESC' }];
    const payload = buildQueryPayload({ baseTable, joins: [], selectedColumns: [], filters: [], sorts, page: 1, limit: 15, distinct: false });
    expect(payload.sorts).toHaveLength(1);
    expect(payload.sorts[0].column).toBe('fecha');
  });
});
