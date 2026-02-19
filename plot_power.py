import pandas as pd
import matplotlib.pyplot as plt
import re

# --- CONFIG ---
COLORS = {
    'bg': '#050505', 'text': '#00F3FF', 'grid': '#222222',
    'power': '#FF0055', 'level': '#00FF41',
    'pulse': '#FFFFFF', # White for haptic events
    'mode_st': '#444444', 'mode_fx': '#D400FF', 'mode_cl': '#00F3FF'
}

plt.rcParams.update({
    'figure.facecolor': COLORS['bg'], 'axes.facecolor': COLORS['bg'],
    'text.color': COLORS['text'], 'axes.labelcolor': COLORS['text'],
    'xtick.color': COLORS['text'], 'ytick.color': COLORS['text'],
    'axes.edgecolor': '#444444', 'grid.color': COLORS['grid'],
    'grid.linestyle': ':'
})

# Columns: Time, State(99=Pulse), Mode, Value(uA or Intensity), Voltage, Level
columns = ['Time', 'State', 'Mode', 'Value', 'Voltage', 'Level']
cleaned_data = []

print("Parsing log file...")

content = ""
for encoding in ['utf-8', 'utf-16', 'latin-1']:
    try:
        with open('power_data.csv', 'r', encoding=encoding) as f:
            content = f.read()
        break
    except UnicodeError: continue

if not content:
    print("Error: Could not read file.")
    exit()

lines = content.splitlines()

for line in lines:
    if "NEON_PWR" not in line: continue
    match = re.search(r'(-?\d+,){5}-?\d+', line)
    if match:
        parts = match.group(0).split(',')
        if len(parts) == 6: cleaned_data.append(parts)

if not cleaned_data:
    print("No valid data found.")
    exit()

# --- PROCESSING ---
df = pd.DataFrame(cleaned_data, columns=columns)
df = df.apply(pd.to_numeric)
df['Time'] = (df['Time'] - df['Time'].iloc[0]) / 1000.0

# SPLIT DATA: 
# State 99 = Pulse Event (Value is Intensity 0-100)
# State 0/1 = Battery Reading (Value is MicroAmps)
df_pulse = df[df['State'] == 99].copy()
df_power = df[df['State'] != 99].copy()

# Fix Current
df_power['Value'] = df_power['Value'].abs()
df_power['Value_Smooth'] = df_power['Value'].rolling(window=8, min_periods=1).mean()

print(f"Plotting {len(df_power)} power readings and {len(df_pulse)} haptic events...")

fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(14, 10), sharex=True)
fig.suptitle('NEON FLUX // HAPTIC & POWER ANALYSIS', fontsize=20, color=COLORS['power'], weight='bold')

# --- GRAPH 1: POWER & PULSES ---
ax1.set_title("Current Draw (µA) + Haptic Triggers", fontsize=10, loc='left', color='gray')

# Plot Power Line
ax1.plot(df_power['Time'], df_power['Value'], color=COLORS['power'], alpha=0.15, linewidth=1)
ax1.plot(df_power['Time'], df_power['Value_Smooth'], color=COLORS['power'], linewidth=2, label='Current (µA)')

# Plot Pulses (Scatter plot along the bottom)
# y position is set to the minimum current to sit at the bottom of the graph
min_y = df_power['Value'].min()
ax1.scatter(df_pulse['Time'], [min_y] * len(df_pulse), 
            color=COLORS['pulse'], marker='|', s=50, alpha=0.6, label='Pulse Event')

ax1.legend(facecolor=COLORS['bg'], edgecolor=COLORS['grid'])
ax1.grid(True)

# --- GRAPH 2: BATTERY LEVEL ---
ax2.set_title("Fuel Level (%)", fontsize=10, loc='left', color='gray')
ax2.plot(df_power['Time'], df_power['Level'], color=COLORS['level'], linewidth=2)
ax2.set_ylim(df_power['Level'].min() - 1, df_power['Level'].max() + 1)
ax2.grid(True)

# --- GRAPH 3: MODE ---
ax3.set_title("Operational Mode", fontsize=10, loc='left', color='gray')
ax3.set_xlabel('Time (Seconds)')
ax3.step(df_power['Time'], df_power['Mode'], where='post', color=COLORS['text'], linewidth=2)
ax3.set_yticks([0, 1, 2])
ax3.set_yticklabels(['STEALTH', 'FLUX', 'CLINICAL'])
ax3.set_ylim(-0.5, 2.5)
ax3.grid(True)

# Colorize Backgrounds
if len(df_power) > 1:
    for i in range(len(df_power) - 1):
        mode = df_power['Mode'].iloc[i]
        c = COLORS['mode_st']
        if mode == 1: c = COLORS['mode_fx']
        elif mode == 2: c = COLORS['mode_cl']
        ax3.axvspan(df_power['Time'].iloc[i], df_power['Time'].iloc[i+1], color=c, alpha=0.2, lw=0)

plt.tight_layout()
plt.show()
