import React, { useState } from 'react';
import { FileSpreadsheet, Search, ChevronLeft, ChevronRight } from 'lucide-react';

export default function SheetTabs({ 
  tables, 
  activeTable, 
  onSelectTable 
}) {
  const [searchQuery, setSearchQuery] = useState('');

  const filteredTables = tables.filter(t => 
    t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    t.schema.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="worksheet-tabs-bar">
      {/* Controles de Navegación de Pestañas (Estilo Excel) */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
        <button 
          className="excel-btn" 
          style={{ padding: '4px 6px', border: 'none', height: '100%' }}
          title="Primera pestaña"
          disabled={filteredTables.length === 0}
          onClick={() => filteredTables.length > 0 && onSelectTable(filteredTables[0])}
        >
          <ChevronLeft size={14} strokeWidth={3} />
        </button>
        <button 
          className="excel-btn" 
          style={{ padding: '4px 6px', border: 'none', height: '100%' }}
          title="Última pestaña"
          disabled={filteredTables.length === 0}
          onClick={() => filteredTables.length > 0 && onSelectTable(filteredTables[filteredTables.length - 1])}
        >
          <ChevronRight size={14} strokeWidth={3} />
        </button>
      </div>

      {/* Listado de Hojas (Tablas) */}
      <div className="tabs-wrapper">
        {filteredTables.length === 0 ? (
          <span style={{ fontSize: '11px', color: 'var(--text-muted)', paddingLeft: '10px', fontStyle: 'italic' }}>
            No se encontraron tablas
          </span>
        ) : (
          filteredTables.map((table) => {
            const isActive = activeTable && activeTable.schema === table.schema && activeTable.name === table.name;
            return (
              <button
                key={`${table.schema}.${table.name}`}
                className={`sheet-tab ${isActive ? 'active' : ''}`}
                onClick={() => onSelectTable(table)}
              >
                <FileSpreadsheet className="sheet-icon" />
                <span>
                  {table.schema === 'dbo' ? table.name : `${table.schema}.${table.name}`}
                </span>
              </button>
            );
          })
        )}
      </div>

      {/* Buscador de "Hojas" integrado */}
      <div style={{ display: 'flex', alignItems: 'center', position: 'relative', width: '220px' }}>
        <Search 
          size={12} 
          style={{ position: 'absolute', left: '8px', color: 'var(--text-muted)' }} 
        />
        <input 
          type="text" 
          placeholder="Buscar hoja..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          style={{
            width: '100%',
            backgroundColor: 'var(--excel-bg-app)',
            border: '1px solid var(--excel-border)',
            borderRadius: '4px',
            color: 'var(--text-primary)',
            fontSize: '11px',
            padding: '4px 8px 4px 26px',
            outline: 'none'
          }}
        />
      </div>
    </div>
  );
}
