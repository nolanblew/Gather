export class InputError extends Error {
  constructor(message, status = 400, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const fail = message => { throw new InputError(message); };
export const object = value => value && typeof value === 'object' && !Array.isArray(value) || fail('Expected an object.');
export function string(value, name, max, required = false) {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) fail(`${name} must be ${required ? 'non-empty text' : 'text'} up to ${max} characters.`);
  return value;
}
export const bool = (value, name) => typeof value === 'boolean' ? value : fail(`${name} must be a boolean.`);
export const id = value => string(value, 'ID', 100, true);
