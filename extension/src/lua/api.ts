import data from '../../data/cc-api.json';

export interface Param {
  name: string;
  type: string;
  optional?: boolean;
  doc?: string;
}

export interface Return {
  type: string;
  name?: string;
  doc?: string;
  optional?: boolean;
}

export interface Overload {
  params: Param[];
  returns?: Return[];
}

export interface Entry {
  kind: 'module' | 'function' | 'value' | 'type';
  doc?: string;
  members?: Record<string, Entry>;
  params?: Param[];
  returns?: Return[];
  returnType?: string;
  valueType?: string;
  value?: string;
  method?: boolean;
  overloads?: Overload[];
  since?: string;
  deprecated?: string;
}

export interface EventEntry {
  doc: string;
  params: Param[];
  since?: string;
}

export interface ApiData {
  version: string;
  globals: Record<string, Entry>;
  modules: Record<string, Entry>;
  types: Record<string, Entry>;
  peripherals: Record<string, Entry>;
  events: Record<string, EventEntry>;
}

export const api = data as unknown as ApiData;

export function typeEntry(name: string | undefined): Entry | undefined {
  if (!name) return undefined;
  const primary = name.split('|')[0].trim();
  return api.types[primary] ?? api.peripherals[primary];
}

export function membersOf(entry: Entry | undefined): Record<string, Entry> | undefined {
  if (!entry) return undefined;
  if (entry.members) return entry.members;
  if (entry.kind === 'value' && entry.returnType) return typeEntry(entry.returnType)?.members;
  return undefined;
}

export function signatures(entry: Entry): Overload[] {
  const main: Overload = { params: entry.params ?? [], returns: entry.returns };
  return [main, ...(entry.overloads ?? [])];
}

export function formatParam(param: Param): string {
  if (param.name === '...') return `...: ${param.type}`;
  return `${param.name}${param.optional ? '?' : ''}: ${param.type}`;
}

export function formatReturns(returns: Return[] | undefined): string {
  if (!returns || returns.length === 0) return '';
  return ' -> ' + returns.map(item => (item.optional ? `${item.type}?` : item.type)).join(', ');
}

export function formatSignature(name: string, entry: Entry, overload: Overload = signatures(entry)[0]): string {
  switch (entry.kind) {
    case 'function':
      return `function ${name}(${overload.params.map(formatParam).join(', ')})${formatReturns(overload.returns)}`;
    case 'module':
      return `module ${name}`;
    case 'type':
      return `type ${name}`;
    default: {
      const type = entry.valueType ?? entry.returnType ?? 'any';
      return entry.value !== undefined ? `${name}: ${type} = ${entry.value}` : `${name}: ${type}`;
    }
  }
}

export function documentation(entry: Entry): string {
  const parts: string[] = [];
  if (entry.deprecated) parts.push(`**Deprecated:** ${entry.deprecated}`);
  if (entry.doc) parts.push(entry.doc);
  const params = (entry.params ?? []).filter(param => param.doc);
  if (params.length > 0) parts.push(params.map(param => `*@param* \`${param.name}\` — ${param.doc}`).join('  \n'));
  const returns = (entry.returns ?? []).filter(item => item.doc);
  if (returns.length > 0) parts.push(returns.map(item => `*@return* \`${item.type}\` — ${item.doc}`).join('  \n'));
  if (entry.since) parts.push(`*Since ${entry.since}*`);
  return parts.join('\n\n');
}
