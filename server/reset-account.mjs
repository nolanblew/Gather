import { resolve } from 'node:path';
import { resetUserPassword } from './auth.mjs';

const args=process.argv.slice(2);
if(args.length!==3 || args[0]!=='--username' || !args[1] || args[2]!=='--confirm') {
  console.error('Usage: set GATHER_RESET_PASSWORD, then node server/reset-account.mjs --username USERNAME --confirm');
  process.exitCode=2;
} else {
  try {
    const password=process.env.GATHER_RESET_PASSWORD;
    if(!password) throw new Error('Set GATHER_RESET_PASSWORD to the new password before running recovery.');
    await resetUserPassword({dataDir:resolve(process.env.DATA_DIR || 'data'),username:args[1],password});
    console.log('Password reset for the named account. Its sessions were revoked; identity, lists and sharing were preserved.');
  } catch(error) {console.error(error.message);process.exitCode=1;}
}
