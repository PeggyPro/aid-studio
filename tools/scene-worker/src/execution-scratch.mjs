import fs from 'node:fs/promises';
import path from 'node:path';

/** Remove only duplicate inputs and temporary files of an execution whose process
 * has exited. Immutable source stores, result directories and ledgers are excluded. */
export async function releaseExecutionScratch(executionRoot, executionDirectory) {
  return releaseOwnedDirectories(executionRoot,executionDirectory,['input','temp']);
}

/** Call only after an authenticated business archive receipt is durably saved. */
export async function releaseArchivedInputs(stateRoot, jobDirectory){
  if(!/^[a-f0-9]{64}$/.test(path.basename(jobDirectory)))throw Error('SCRATCH_SCOPE_INVALID');
  return releaseOwnedDirectories(stateRoot,jobDirectory,['source','resource']);
}

async function releaseOwnedDirectories(executionRoot,executionDirectory,names){
  const root=await fs.realpath(executionRoot),folder=await fs.realpath(executionDirectory);
  const relative=path.relative(root,folder);
  if(!relative||relative.startsWith('..')||path.isAbsolute(relative)||path.dirname(folder)!==root
      ||(await fs.lstat(executionDirectory)).isSymbolicLink())throw Error('SCRATCH_SCOPE_INVALID');
  let bytes=0,files=0;
  async function remove(file){
    const stat=await fs.lstat(file);
    // Never follow links placed by an executed source file.
    if(stat.isDirectory()&&!stat.isSymbolicLink()){
      for(const name of await fs.readdir(file))await remove(path.join(file,name));
      await fs.rmdir(file);
    }else{await fs.unlink(file);files++;bytes+=stat.isSymbolicLink()?0:stat.size;}
  }
  for(const name of names){
    const target=path.join(folder,name);
    try{await remove(target);}catch(error){if(error.code!=='ENOENT')throw error;}
    // A concurrent remover may have raced on a child; that alone is not proof
    // that this entire scratch directory is gone.
    try{await fs.lstat(target);throw Error('SCRATCH_RELEASE_INCOMPLETE');}
    catch(error){if(error.code!=='ENOENT')throw error;}
  }
  return {files,bytes};
}
