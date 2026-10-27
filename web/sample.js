// 示例仅处理业务交互；浏览器不保存 OAuth token，权限由后端检查。
async function csrf() {
  const response = await fetch('/bff/web/csrf', {credentials: 'same-origin'});
  if (!response.ok) throw new Error('业务 BFF 尚未配置，请先完成项目接入步骤');
  return (await response.json()).data;
}

document.querySelector('#login').addEventListener('submit', async event => {
  event.preventDefault();
  try {
    document.querySelector('#login-csrf').value = (await csrf()).token;
    event.target.submit();
  } catch (error) {
    document.querySelector('#login-error').textContent = error.message;
  }
});

async function call(method, peer = false) {
  const result = document.querySelector('#result');
  try {
    const service = document.querySelector('#service').value;
    const headers = {'Accept': 'application/json'};
    const options = {method, credentials: 'same-origin', headers};
    if (method === 'POST') {
      const token = await csrf();
      headers[token.header_name] = token.token;
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify({name: document.querySelector('#name').value});
    }
    const response = await fetch(`/bff/web/api/v1/${service}-items${peer ? '/peer' : ''}`, options);
    const text = await response.text();
    result.textContent = `HTTP ${response.status}\n${text}`;
  } catch (error) {
    result.textContent = error.message;
  }
}
document.querySelector('#create').onclick = () => call('POST');
document.querySelector('#list').onclick = () => call('GET');
document.querySelector('#peer').onclick = () => call('GET', true);
