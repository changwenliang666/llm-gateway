const AXIS = "#8aa0b8";
const LINE = "rgba(62, 224, 255, 0.35)";
const SPLIT = "rgba(62, 224, 255, 0.12)";
const TEXT = "#e7f3ff";
const CYAN = "#3ee0ff";

const STATUS_COLORS = {
  SUCCEEDED: "#3ee0ff",
  FAILED: "#ff5d7a",
  PENDING: "#f5c16c",
  RUNNING: "#7aa2ff",
  UNKNOWN: "#8aa0b8"
};

function base(title) {
  return {
    backgroundColor: "transparent",
    title: {
      text: title,
      left: 0,
      textStyle: { color: TEXT, fontSize: 14, fontWeight: 500 }
    },
    tooltip: {
      backgroundColor: "#101a2e",
      borderColor: LINE,
      textStyle: { color: TEXT }
    },
    grid: { left: 52, right: 16, top: 48, bottom: 28 }
  };
}

function axes() {
  return {
    xAxis: {
      type: "category",
      data: [],
      axisLabel: { color: AXIS },
      axisLine: { lineStyle: { color: LINE } }
    },
    yAxis: {
      type: "value",
      axisLabel: { color: AXIS },
      splitLine: { lineStyle: { color: SPLIT } }
    }
  };
}

export function barOption(title, rows) {
  const option = base(title);
  Object.assign(option, axes());
  option.xAxis.data = rows.map((row) => row.name);
  option.series = [
    {
      type: "bar",
      data: rows.map((row) => row.value),
      itemStyle: { color: CYAN, borderRadius: [3, 3, 0, 0] },
      barMaxWidth: 28
    }
  ];
  return option;
}

export function lineOption(title, rows) {
  const option = base(title);
  Object.assign(option, axes());
  option.xAxis.data = rows.map((row) => row.name);
  option.series = [
    {
      type: "line",
      data: rows.map((row) => row.value),
      smooth: true,
      showSymbol: rows.length < 20,
      lineStyle: { color: CYAN, width: 2 },
      itemStyle: { color: CYAN },
      areaStyle: { color: "rgba(62, 224, 255, 0.12)" }
    }
  ];
  return option;
}

export function statusOption(title, rows) {
  return {
    ...base(title),
    series: [
      {
        type: "pie",
        radius: ["46%", "70%"],
        center: ["50%", "58%"],
        label: { color: TEXT },
        data: rows.map((row) => ({
          name: row.name,
          value: row.value,
          itemStyle: { color: STATUS_COLORS[row.name] || AXIS }
        }))
      }
    ]
  };
}
