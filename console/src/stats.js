export function formatTokens(value) {
  return new Intl.NumberFormat("zh-CN").format(Number(value || 0));
}

export function modelRows(models) {
  const counts = new Map();
  for (const model of models) {
    counts.set(model.modelName, (counts.get(model.modelName) || 0) + 1);
  }
  return models.map((model) => ({
    name: counts.get(model.modelName) > 1 ? `${model.modelName} · ${model.providerName}` : model.modelName,
    value: Number(model.consumedTokens || 0)
  }));
}

export function providerRows(models) {
  const totals = new Map();
  for (const model of models) {
    const name = model.providerName || "未命名供应商";
    totals.set(name, (totals.get(name) || 0) + Number(model.consumedTokens || 0));
  }
  return [...totals.entries()].map(([name, value]) => ({ name, value }));
}

export function sumTokens(models) {
  return models.reduce((sum, model) => sum + Number(model.consumedTokens || 0), 0);
}
