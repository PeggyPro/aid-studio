"""Trusted namespace supervisor. Generated programs receive neither the export FD nor host paths."""
import ctypes,filecmp,json,os,pathlib,shutil,signal,stat,struct,subprocess,sys

# Generated children must not reopen the supervisor's export stream through procfs.
if ctypes.CDLL(None,use_errno=True).prctl(4,0,0,0,0)!=0:
    raise RuntimeError('SUPERVISOR_PROTECTION_REQUIRED')

configuration=json.loads(pathlib.Path('/aid-control.json').read_text())
folder=pathlib.Path(configuration['folder']);seed=pathlib.Path('/aid-seed')
readonly=configuration.get('readonlyInput')
count=0;total=0
for base,dirs,files in os.walk(seed,followlinks=False):
    relative=pathlib.Path(base).relative_to(seed)
    if any((pathlib.Path(base)/name).is_symlink() for name in dirs):raise RuntimeError('NATIVE_SEED_LINK')
    target=folder/relative;target.mkdir(parents=True,exist_ok=True)
    for name in files:
        origin=pathlib.Path(base)/name;info=origin.lstat();count+=1;total+=info.st_size
        if not stat.S_ISREG(info.st_mode) or count>2048 or total>configuration['scratchBytes']:raise RuntimeError('NATIVE_SEED_LIMIT')
        destination=target/name
        if readonly and destination.is_relative_to(readonly):continue
        shutil.copyfile(origin,destination)
temporary=folder/'.native-tmp';temporary.mkdir(exist_ok=True)
environment={**configuration['env'],'TMPDIR':str(temporary),'TEMP':str(temporary),'TMP':str(temporary),'HOME':str(temporary),
             'PYTHONNOUSERSITE':'1','PYTHONUTF8':'1','OMP_NUM_THREADS':str(configuration['threads']),
             'OPENBLAS_NUM_THREADS':str(configuration['threads']),'UV_THREADPOOL_SIZE':str(configuration['threads'])}
process=subprocess.Popen(configuration['argv'],cwd=configuration['cwd'],env=environment,stdout=sys.stderr,stderr=sys.stderr,close_fds=True,start_new_session=True)
try:
    guard=configuration.get('timeoutMs')
    code=process.wait(timeout=guard/1000 if guard is not None else None)
except subprocess.TimeoutExpired:
    os.killpg(process.pid,signal.SIGKILL)
    process.wait()
    sys.exit(124)
if code:
    sys.exit(code if code>0 else 1)

writer=os.fdopen(1,'wb',buffering=0);writer.write(b'AIDNATIVE1\n')
output=pathlib.Path(configuration['output']);maximum=configuration['scratchBytes'];total=0;count=0
for base,dirs,files in os.walk(output,followlinks=False):
    dirs[:]=[name for name in dirs if name!='.native-tmp']
    if any((pathlib.Path(base)/name).is_symlink() for name in dirs):raise RuntimeError('NATIVE_OUTPUT_LINK')
    for name in sorted(files):
        file=pathlib.Path(base)/name
        if file==pathlib.Path(configuration['logFile']):continue
        relative=file.relative_to(folder);initial=seed/relative
        info=file.lstat()
        if not stat.S_ISREG(info.st_mode):raise RuntimeError('NATIVE_OUTPUT_TYPE')
        if initial.exists():
            if info.st_size!=initial.stat().st_size or not filecmp.cmp(file,initial,shallow=False):raise RuntimeError('NATIVE_INPUT_CHANGED')
            continue
        total+=info.st_size;count+=1
        if total>maximum or count>1024:raise RuntimeError('NATIVE_OUTPUT_LIMIT')
        label=file.relative_to(output).as_posix()
        header=json.dumps({'name':label,'bytes':info.st_size},ensure_ascii=True,separators=(',',':')).encode()
        writer.write(struct.pack('!I',len(header)));writer.write(header)
        descriptor=os.open(file,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(descriptor,'rb') as reader:
            current=os.fstat(reader.fileno())
            if current.st_ino!=info.st_ino or current.st_size!=info.st_size or not stat.S_ISREG(current.st_mode):raise RuntimeError('NATIVE_OUTPUT_CHANGED')
            remaining=info.st_size
            while remaining:
                chunk=reader.read(min(65536,remaining))
                if not chunk:raise RuntimeError('NATIVE_OUTPUT_TRUNCATED')
                writer.write(chunk);remaining-=len(chunk)
writer.write(struct.pack('!I',0));writer.close()
