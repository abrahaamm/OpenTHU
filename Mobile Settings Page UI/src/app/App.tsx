import { Shield, Network, BookOpen, Brain, Monitor, ChevronRight } from 'lucide-react';
import { useState } from 'react';

export default function App() {
  const [selectedTab, setSelectedTab] = useState('settings');
  const [isAnimating, setIsAnimating] = useState(false);

  const tabs = [
    { id: 'chat', label: '对话' },
    { id: 'plan', label: '规划' },
    { id: 'settings', label: '设置' },
  ];

  const getSliderPosition = () => {
    const index = tabs.findIndex(tab => tab.id === selectedTab);
    // 让滑块在每个1/3区域内完美居中
    // 滑块应该在区域中心，左右留相等间距
    return `calc(${index * 33.333}% + 5px)`;
  };

  const handleTabClick = (tabId) => {
    if (tabId !== selectedTab) {
      setIsAnimating(true);
      setSelectedTab(tabId);
      setTimeout(() => setIsAnimating(false), 400);
    }
  };

  const settingItems = [
    {
      id: 1,
      icon: Shield,
      label: '能力与隐私',
    },
    {
      id: 2,
      icon: Network,
      label: '连接与模型',
    },
    {
      id: 3,
      icon: BookOpen,
      label: '校园与搜索',
    },
    {
      id: 4,
      icon: Brain,
      label: '记忆',
    },
    {
      id: 5,
      icon: Monitor,
      label: '设备与系统',
    },
  ];

  return (
    <div className="min-h-screen flex items-center justify-center" style={{ backgroundColor: '#F8F4FA' }}>
      <div className="w-full max-w-[400px] px-5 py-8">
        {/* Page Title */}
        <h1
          className="text-center mb-8"
          style={{
            color: '#660874',
            fontSize: '28px',
            fontWeight: '500',
            letterSpacing: '0.02em',
            fontFamily: '"PingFang SC", "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", -apple-system, sans-serif'
          }}
        >
          设置
        </h1>

        {/* Segmented Control */}
        <div className="flex justify-center mb-8">
          <div
            className="relative"
            style={{
              width: '320px',
              height: '54px',
              backgroundColor: '#F0EBF3',
              borderRadius: '999px',
              padding: '5px',
              boxShadow: 'inset 0 2px 4px rgba(102, 8, 116, 0.12), inset 0 1px 2px rgba(102, 8, 116, 0.08)',
            }}
          >
            {/* Sliding Button */}
            <div
              className="absolute"
              style={{
                left: getSliderPosition(),
                top: '5px',
                width: 'calc(33.333% - 10px)',
                height: 'calc(100% - 10px)',
                background: 'linear-gradient(180deg, #FFFFFF 0%, #FEFEFE 50%, #F9F9F9 100%)',
                borderRadius: '999px',
                boxShadow: `
                  0 0 0 1px rgba(242, 201, 76, 0.2),
                  0 0 12px rgba(242, 201, 76, 0.25),
                  0 0 20px rgba(242, 201, 76, 0.15),
                  0 1px 1px rgba(255, 255, 255, 0.8) inset,
                  0 -1px 1px rgba(0, 0, 0, 0.03) inset,
                  0 3px 6px rgba(102, 8, 116, 0.12),
                  0 6px 12px rgba(102, 8, 116, 0.08),
                  0 1px 2px rgba(102, 8, 116, 0.15)
                `,
                transition: 'left 0.4s cubic-bezier(0.4, 0, 0.2, 1)',
                animation: isAnimating ? 'squashStretch 0.4s cubic-bezier(0.4, 0, 0.2, 1)' : 'none',
              }}
            />

            {/* Tab Labels */}
            <div className="relative h-full flex">
              {tabs.map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => handleTabClick(tab.id)}
                  className="flex-1 flex items-center justify-center transition-colors duration-200"
                  style={{
                    color: selectedTab === tab.id ? '#660874' : '#6B5C70',
                    fontSize: '15px',
                    fontWeight: selectedTab === tab.id ? '600' : '500',
                    fontFamily: '"PingFang SC", "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", -apple-system, sans-serif',
                    zIndex: 10,
                  }}
                >
                  {tab.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Settings List */}
        <div
          className="overflow-hidden rounded-2xl"
          style={{
            backgroundColor: '#FFFFFF',
            boxShadow: '0 4px 16px rgba(102, 8, 116, 0.08), 0 2px 8px rgba(102, 8, 116, 0.04)',
          }}
        >
          {settingItems.map((item, index) => {
            const Icon = item.icon;
            const isLast = index === settingItems.length - 1;
            return (
              <div key={item.id}>
                <button
                  className="w-full flex items-center gap-4 px-5 py-4 transition-all duration-200 hover:bg-opacity-50 active:bg-opacity-70"
                  style={{
                    backgroundColor: 'transparent',
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = 'rgba(248, 244, 250, 0.6)';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = 'transparent';
                  }}
                >
                  {/* Icon */}
                  <div style={{ color: '#660874' }}>
                    <Icon size={22} strokeWidth={1.8} />
                  </div>

                  {/* Label */}
                  <span
                    className="flex-1 text-left"
                    style={{
                      color: '#1F1F1F',
                      fontSize: '16px',
                      fontWeight: '500',
                    }}
                  >
                    {item.label}
                  </span>

                  {/* Chevron */}
                  <div style={{ color: '#F2C94C' }}>
                    <ChevronRight size={20} strokeWidth={2} />
                  </div>
                </button>

                {/* Divider */}
                {!isLast && (
                  <div
                    className="mx-5"
                    style={{
                      height: '1px',
                      backgroundColor: 'rgba(102, 8, 116, 0.08)',
                    }}
                  />
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}