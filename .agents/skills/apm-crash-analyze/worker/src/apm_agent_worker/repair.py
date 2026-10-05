"""明确授权的有限文本补丁、写前日志和幂等对账；不执行项目命令。"""

import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
from uuid import uuid4
from pydantic import Field, ValidationError
from apm_agent_worker.analysis import StrictModel
from apm_agent_worker.platform import WorkerError
from apm_agent_worker.redaction import redact
from apm_agent_worker.source import excluded_path, read_current, relative_path, secret_content, SourceBlocked
from apm_agent_worker.state import RunState

# 一次补丁预算，不允许整仓库批量修改。
MAX_PATCH_BYTES = 1024 * 1024
# 新建只限源码或测试文件，不创建配置秘密或二进制材料。
NEW_SUFFIXES = {'.kt','.kts','.java','.py','.ts','.tsx','.js','.jsx','.vue','.cpp','.c','.h','.cs','.swift','.rs','.go'}


class Replacement(StrictModel):
    """宿主指定读过的行范围和精确原文，工具核验真实内容。"""
    startLine: int = Field(ge=1)  # 一起始行。
    endLine: int = Field(ge=1)  # 含结束行。
    expected: str  # read 返回的原文，不含范围末尾换行。
    replacement: str  # 必要替换内容，不能夹带秘密。


class FilePatch(StrictModel):
    """现有文件只接受有限行替换，新文件明确给出 create。"""
    path: str = Field(min_length=1,max_length=512)  # 当前项目相对路径。
    edits: list[Replacement] = Field(default_factory=list,max_length=20)  # 非重叠修改范围。
    create: str | None = None  # 新建源码；必须原本不存在。


class Patch(StrictModel):
    """一次任务只接受一个有界补丁，恢复不能换补丁自动继续。"""
    files: list[FilePatch] = Field(min_length=1,max_length=20)  # 最多二十个文件。


def digest(content: bytes) -> str:
    """原始字节的可信 SHA-256。"""
    return hashlib.sha256(content).hexdigest()


def manifest(state: RunState) -> dict:
    """只从私有快照读取有界文件清单，宿主不能自填摘要。"""
    path = Path(state.data['sourceDirectory'])/'manifest.json'  # 本次材料目录。
    if path.is_symlink() or path.stat().st_size > 64*1024*1024:
        raise WorkerError('STATE_UNSAFE')
    return json.loads(path.read_text(encoding='utf-8'))


def validate_reads(state: RunState, after: dict[str,str] | None = None) -> bool:
    """检查所有实际读过的相关文件，验证或写入前变化即冲突。"""
    files = manifest(state)  # 私有快照字节摘要。
    expected = after or {}  # 修复后文件以实际写后摘要核验。
    for name in {item['path'] for item in state.data.get('reads', [])} | set(expected):
        try:
            entry = read_current(Path(state.data['projectRoot']),name)
        except SourceBlocked:
            return False
        if entry is None or digest(entry[0]) != expected.get(name,files.get(name,{}).get('sha256')):
            return False
    return True


def journal_path(state: RunState) -> Path:
    """日志按平台和任务私有身份固定，不接受宿主传入路径。"""
    return state.path.with_suffix('.patch.json')


def save_journal(state: RunState, value: dict) -> None:
    """写前事实原子持久化，未落盘不得开始编辑。"""
    path = journal_path(state)  # 私有任务日志。
    temporary = path.with_suffix('.'+uuid4().hex+'.tmp')  # 随机临时文件，不覆盖未知内容。
    fd = os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    try:
        with os.fdopen(fd,'w',encoding='utf-8') as stream:
            json.dump(value,stream,ensure_ascii=False)
            stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,path)
        parent = os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)  # 持久化目录项。
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        temporary.unlink(missing_ok=True)


def load_journal(state: RunState) -> dict | None:
    """恢复原补丁事实，拒绝链接或超限日志。"""
    path = journal_path(state)  # 与 RunState 同一私有目录。
    if path.is_symlink():
        raise WorkerError('STATE_UNSAFE')
    if not path.exists():
        return None
    if path.stat().st_size > 2*MAX_PATCH_BYTES or path.stat().st_mode & 0o077:
        raise WorkerError('STATE_UNSAFE')
    value = json.loads(path.read_text(encoding='utf-8'))  # 仅本工具写入的日志。
    if value['snapshotId'] != state.data['snapshotId'] or value['runId'] != state.data['runId']:
        raise WorkerError('REFERENCE_INVALID')
    return value


def transformed(content: bytes, edits: list[Replacement]) -> bytes:
    """精确行替换保留未读区域与原换行形式，拒绝重叠范围。"""
    text = content.decode('utf-8')  # 仅普通 UTF-8 源码。
    lines = text.splitlines(keepends=True)  # 保留原文件的 CRLF/LF 和末尾换行。
    last = 0  # 用于拒绝重叠或倒序范围。
    ranges = sorted(edits,key=lambda edit:edit.startLine)  # 可核验的顺序，不依赖宿主顺序。
    for edit in ranges:
        if edit.startLine<=last or edit.endLine<edit.startLine or edit.endLine>len(lines):
            raise WorkerError('REFERENCE_INVALID')
        last = edit.endLine
        selected = ''.join(lines[edit.startLine-1:edit.endLine])  # 原字节对应的行范围。
        expected = '\n'.join(selected.splitlines())  # 与 read 交付形式一致。
        if expected!=edit.expected or redact(expected)!=expected or redact(edit.replacement)!=edit.replacement or '\0' in edit.replacement:
            raise WorkerError('PATCH_CONTENT_INVALID')
    for edit in reversed(ranges):
        selected = ''.join(lines[edit.startLine-1:edit.endLine])  # 不受之前高行替换影响。
        newline = '\r\n' if selected.endswith('\r\n') else '\n' if selected.endswith('\n') else ''  # 保留末尾状态。
        replacement = edit.replacement.replace('\r\n','\n')  # 宿主行文本形式。
        if newline=='\r\n':
            replacement = replacement.replace('\n','\r\n')
        if replacement and newline and not replacement.endswith(newline):
            replacement += newline
        lines[edit.startLine-1:edit.endLine] = [replacement]
    return ''.join(lines).encode('utf-8')


def parent_descriptor(root: Path, name: str) -> tuple[int,str]:
    """打开真实项目内父目录，禁止链接及目录竞争越界。"""
    parts = relative_path(name).parts  # 原始相对路径不做宽松规整。
    fd = os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)  # 项目根描述符。
    try:
        for part in parts[:-1]:
            child = os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)  # 不自动创建未核验目录。
            os.close(fd);fd=child
        return fd,parts[-1]
    except BaseException:
        os.close(fd)
        raise


def atomic_write(root: Path, name: str, content: bytes, mode: int, expected_hash: str | None) -> None:
    """逐文件原子写入；最终路径再核验，不能把多文件伪装成事务。"""
    fd,leaf = parent_descriptor(root,name)  # 路径组件已经禁止链接。
    temporary = '.apm-patch-'+uuid4().hex  # 同目录随机临时文件。
    file_fd = None  # 临时普通文件描述符。
    try:
        current = read_current(root,name,missing_ok=True)  # 写前重新核验当前文件。
        if (None if current is None else digest(current[0]))!=expected_hash:
            raise WorkerError('WORKSPACE_CONFLICT')
        file_fd = os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=fd)
        with os.fdopen(file_fd,'wb') as stream:
            file_fd = None
            stream.write(content);stream.flush();os.fchmod(stream.fileno(),mode);os.fsync(stream.fileno())
        # 检查父目录仍是根内原路径；描述符不会通过新链接写到项目外。
        check_fd,_ = parent_descriptor(root,name)
        try:
            if (os.fstat(fd).st_dev,os.fstat(fd).st_ino)!=(os.fstat(check_fd).st_dev,os.fstat(check_fd).st_ino):
                raise WorkerError('WORKSPACE_CONFLICT')
        finally:
            os.close(check_fd)
        current = read_current(root,name,missing_ok=True)  # 原子发布前的最后一致性检查。
        if (None if current is None else digest(current[0]))!=expected_hash:
            raise WorkerError('WORKSPACE_CONFLICT')
        if expected_hash is None:
            # hard link 的不存在条件为原子操作，新文件并发出现绝不会覆盖。
            os.link(temporary,leaf,src_dir_fd=fd,dst_dir_fd=fd,follow_symlinks=False)
            os.unlink(temporary,dir_fd=fd)
        else:
            os.replace(temporary,leaf,src_dir_fd=fd,dst_dir_fd=fd)
        os.fsync(fd)
    finally:
        if file_fd is not None:
            os.close(file_fd)
        try:
            os.unlink(temporary,dir_fd=fd)
        except FileNotFoundError:
            pass
        os.close(fd)


def facts(state: RunState, value: dict | None = None) -> dict:
    """恢复只核对当前字节，不重复执行补丁。"""
    value = value or load_journal(state)  # 原写前记录。
    if value is None and state.data.get('repairFailure'):
        return {'status':state.data['repairFailure'],'files':[],'reason':'写入前工作区冲突，未修改代码'}
    if value is None:
        return {'status':'NOT_APPLICABLE' if state.data.get('repairAuthorized') else 'NOT_REQUESTED','files':[],
                'reason':'未执行修改' if state.data.get('repairAuthorized') else '仅分析'}
    written = []  # 可证实已写入的修改。
    conflict = False  # 外部变化不能覆盖或自动撤回。
    for file in value['files']:
        try:
            current = read_current(Path(state.data['projectRoot']),file['path'],missing_ok=True)
            current_hash = None if current is None else digest(current[0])
        except SourceBlocked:
            current_hash = 'unsafe'
        if current_hash==file['afterSha256']:
            written.append({key:file[key] for key in ('path','beforeSha256','afterSha256')})
        elif current_hash!=file['beforeSha256']:
            conflict = True
    status = 'CONFLICT' if conflict else 'APPLIED' if len(written)==len(value['files']) else 'PARTIAL' if written else 'FAILED'
    if value.get('reverted'):
        status = 'FAILED'
    return {'status':status,'files':written,'reason':'工作区存在后续修改' if conflict else '本次修改已安全撤回' if value.get('reverted') else '按私有写前记录核验实际内容'}


def apply(platform, state: RunState, text: str) -> dict:
    """只有明确修复分配可写；相同补丁重试只返回实际对账。"""
    from apm_agent_worker.host import active
    active(platform,state)
    if not state.data.get('repairAuthorized'):
        raise WorkerError('REPAIR_NOT_AUTHORIZED')
    if len(text.encode('utf-8'))>MAX_PATCH_BYTES:
        raise WorkerError('PATCH_TOO_LARGE')
    try:
        patch = Patch.model_validate_json(text)  # 不删除未知字段或放宽输入。
    except ValidationError:
        raise WorkerError('PATCH_INVALID') from None
    normalized = json.dumps(patch.model_dump(mode='json'),ensure_ascii=False,separators=(',',':'))  # 同一语义补丁稳定摘要。
    patch_hash = digest(normalized.encode())  # 用于丢失本地确认后的对账。
    previous = load_journal(state)  # 旧日志存在时不允许第二次执行。
    if previous is not None:
        if previous['patchSha256']!=patch_hash:
            raise WorkerError('PATCH_CONFLICT')
        return facts(state,previous)
    if len({file.path for file in patch.files})!=len(patch.files):
        raise WorkerError('PATCH_INVALID')
    lock_directory = project_lock_directory()
    lock_path = lock_directory/('project-'+digest(state.data['projectRoot'].encode())+'.lock')  # 同一私有目录协调项目内写入。
    lock = os.open(lock_path,os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)  # 不把锁放进源码。
    try:
        try:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            raise WorkerError('PROJECT_BUSY') from None
        if not validate_reads(state):
            state.save(repairFailure='CONFLICT')
            raise WorkerError('WORKSPACE_CONFLICT')
        materials = manifest(state)  # 同快照的原始字节摘要。
        planned = []  # 已核验有限补丁，每文件只缓存有界源码。
        for file in patch.files:
            if excluded_path(file.path):
                raise WorkerError('PATCH_PATH_INVALID')
            current = read_current(Path(state.data['projectRoot']),file.path,missing_ok=True)  # 不存在必须实际确认。
            if file.create is not None:
                if file.edits or current is not None or file.path in materials or Path(file.path).suffix.lower() not in NEW_SUFFIXES:
                    raise WorkerError('PATCH_CONTENT_INVALID')
                content = file.create.encode('utf-8')  # 新源码仍受秘密和大小边界约束。
                before_hash,mode = None,0o644
                if redact(file.create)!=file.create or '\0' in file.create or secret_content(content):
                    raise WorkerError('PATCH_CONTENT_INVALID')
            else:
                if not file.edits or current is None or file.path not in materials or digest(current[0])!=materials[file.path]['sha256']:
                    raise WorkerError('WORKSPACE_CONFLICT')
                for edit in file.edits:
                    if not any(item['path']==file.path and item['startLine']<=edit.startLine and item['endLine']>=edit.endLine for item in state.data.get('reads',[])):
                        raise WorkerError('REFERENCE_INVALID')
                content = transformed(current[0],file.edits)  # 只替换实际读过的精确原文。
                before_hash,mode = digest(current[0]),current[1]
            if secret_content(content):
                raise WorkerError('PATCH_CONTENT_INVALID')
            if len(content)>16*1024*1024:
                raise WorkerError('PATCH_TOO_LARGE')
            if digest(content)==before_hash:
                raise WorkerError('PATCH_CONTENT_INVALID')
            planned.append({'path':file.path,'beforeSha256':before_hash,'afterSha256':digest(content),'mode':mode,'content':content})
        value = {'runId':state.data['runId'],'snapshotId':state.data['snapshotId'],'patchSha256':patch_hash,
                 'patch':patch.model_dump(mode='json'),'files':[{key:item[key] for key in ('path','beforeSha256','afterSha256','mode')} for item in planned]}
        save_journal(state,value)
        for file in planned:
            active(platform,state)  # 每次写入前检查取消、到期、撤销和看护。
            if not validate_reads(state,{item['path']:item['afterSha256'] for item in value['files'] if item.get('written')}):
                raise WorkerError('WORKSPACE_CONFLICT')
            atomic_write(Path(state.data['projectRoot']),file['path'],file['content'],file['mode'],file['beforeSha256'])
            next(item for item in value['files'] if item['path']==file['path'])['written']=True
            save_journal(state,value)  # 中断窗口也可通过真实文件摘要恢复。
        return facts(state,value)
    finally:
        fcntl.flock(lock,fcntl.LOCK_UN);os.close(lock)


def project_lock_directory() -> Path:
    """同一用户的所有状态目录共享项目写锁，不能换 state-dir 并行绕过。"""
    directory = Path.home()/'.local/state/apm-analysis-project-locks'  # 固定私有锁位置。
    if directory.is_symlink():
        raise WorkerError('STATE_UNSAFE')
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    if directory.stat().st_uid!=os.getuid():
        raise WorkerError('STATE_UNSAFE')
    directory.chmod(0o700)
    return directory


def safe_text(text: str, project: str) -> str:
    """有限命令和摘要去掉秘密参数与本机路径，不生成原始日志。"""
    output = redact(text).replace(project,'.')  # 项目内绝对位置替换为相对位置。
    output = re.sub(r'(?i)(--?(?:api[-_]?key|app[-_]?key|password|secret|token|authorization)(?:=|\s+))[^\s]+',r'\1[REDACTED]',output)
    output = re.sub(r'\b([A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD)\s*=\s*)[^\s]+',r'\1[REDACTED]',output)
    output = re.sub(r"(?<![\w.])/(?:[^\s\"']+)",'[LOCAL_PATH]',output)  # Unix 绝对路径。
    output = re.sub(r"[A-Za-z]:[\\/][^\s\"']+",'[LOCAL_PATH]',output)  # Windows 绝对路径。
    for key,value in os.environ.items():
        if re.search(r'(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)',key,re.I) and len(value)>=8:
            output = output.replace(value,'[REDACTED]')
    return output


def verification(state: RunState, requested) -> dict:
    """验证自报与当前文件一致性分开记录，不冒充独立 CI。"""
    journal = load_journal(state)  # 实际修改的写后材料。
    after = {file['path']:file['afterSha256'] for file in journal['files']} if journal else {}
    unchanged = validate_reads(state,after)  # 验证后再核对相关文件。
    if requested is None:
        return {'status':'NOT_RUN','metadataSource':'HOST_REPORTED','commands':[],'reason':'未执行验证','workspaceUnchanged':unchanged}
    commands = [item.model_dump() for item in requested.commands]  # 宿主有限命令事实。
    if (requested.status=='NOT_RUN' and (commands or not requested.reason.strip())) or (requested.status=='PASSED' and (not commands or any(item['exitCode']!=0 for item in commands))) \
            or (requested.status=='FAILED' and (not commands or all(item['exitCode']==0 for item in commands))):
        raise WorkerError('FORMAT_INVALID')
    for command in commands:
        command['command'] = safe_text(command['command'],state.data['projectRoot'])
        command['summary'] = safe_text(command['summary'],state.data['projectRoot'])
    status = 'FAILED' if requested.status=='PASSED' and not unchanged else requested.status  # 不声称变化后的工作区已通过。
    reason = safe_text(requested.reason,state.data['projectRoot'])
    if not unchanged:
        reason = ('验证期间相关文件变化，命令结果不能证明当前工作区通过。'+reason)[:2000]
    return {'status':status,'metadataSource':'HOST_REPORTED','commands':commands,'reason':reason,'workspaceUnchanged':unchanged}


def revert(platform,state: RunState) -> dict:
    """仅活动租约下撤回本工具仍持有的精确写后内容，不重置其他改动。"""
    from apm_agent_worker.host import active
    active(platform,state)
    if not state.data.get('repairAuthorized'):
        raise WorkerError('REPAIR_NOT_AUTHORIZED')
    value = load_journal(state)  # 必须有本次确切写前事实。
    if value is None or value.get('reverted'):
        raise WorkerError('PATCH_CONFLICT')
    lock = os.open(project_lock_directory()/('project-'+digest(state.data['projectRoot'].encode())+'.lock'),os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        try:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:
            raise WorkerError('PROJECT_BUSY') from None
        # 先检查全部已完成文件，后续用户变化时不执行任何整体撤回。
        completed = []  # 只有精确等于工具写后字节才可撤回。
        for file in value['files']:
            current = read_current(Path(state.data['projectRoot']),file['path'],missing_ok=True)
            current_hash = None if current is None else digest(current[0])
            if current_hash==file['afterSha256']:
                completed.append(file)
            elif current_hash!=file['beforeSha256']:
                raise WorkerError('WORKSPACE_CONFLICT')
        value['revertRequested'] = True
        save_journal(state,value)
        for file in completed:
            active(platform,state)
            if file['beforeSha256'] is None:
                fd,leaf = parent_descriptor(Path(state.data['projectRoot']),file['path'])
                try:
                    current = read_current(Path(state.data['projectRoot']),file['path'])
                    if digest(current[0])!=file['afterSha256']:
                        raise WorkerError('WORKSPACE_CONFLICT')
                    check_fd,_ = parent_descriptor(Path(state.data['projectRoot']),file['path'])  # 撤回同样拒绝父目录替换。
                    try:
                        if (os.fstat(fd).st_dev,os.fstat(fd).st_ino)!=(os.fstat(check_fd).st_dev,os.fstat(check_fd).st_ino):
                            raise WorkerError('WORKSPACE_CONFLICT')
                    finally:
                        os.close(check_fd)
                    os.unlink(leaf,dir_fd=fd);os.fsync(fd)  # 仅删除本工具本次创建且字节未变的文件。
                finally:
                    os.close(fd)
            else:
                original = (Path(state.data['sourceDirectory'])/'original'/file['path']).read_bytes()
                if digest(original)!=file['beforeSha256']:
                    raise WorkerError('REFERENCE_INVALID')
                atomic_write(Path(state.data['projectRoot']),file['path'],original,file['mode'],file['afterSha256'])
            file['reverted'] = True
            save_journal(state,value)
        value['reverted'] = True
        save_journal(state,value)
        return facts(state,value)
    finally:
        fcntl.flock(lock,fcntl.LOCK_UN);os.close(lock)
