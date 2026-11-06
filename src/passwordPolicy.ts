export const passwordHint = 'At least 6 characters and any two of uppercase, lowercase, numbers, or symbols.';
export function validNewPassword(value: string): boolean {
  return value.length >= 6 && value.length <= 128 && [/\p{Lu}/u, /\p{Ll}/u, /\p{N}/u, /[^\p{L}\p{N}\s]/u].filter(pattern => pattern.test(value)).length >= 2;
}
