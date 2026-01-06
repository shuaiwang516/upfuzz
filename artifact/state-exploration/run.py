#!/usr/bin/env python3
import re
import numpy as np
import matplotlib.pyplot as plt
from scipy.interpolate import interp1d
import sys
import matplotlib.ticker as ticker
import os

useQueueSize = False
countMultiVD = True
onlyCountMultiVD = False

start_from_zero = True
x_start_from_zero = False
y_start_from_zero = True

draw_yaxis = False
draw_legend = True
draw_title = True

x_my_font_size = 40
y_my_font_size = 40
title_my_font_size = 40
my_legend_font_size = 27
my_marker_size = 8
my_line_width = 5

time_pattern = re.compile(r"run time\s*:\s*(\d+)s")
queue_pattern = re.compile(r"QueueType\s*:\s*(\S+).*queue size\s*:\s*(\d+)")

format_num_pattern = re.compile(r"format num\s*:\s*(\d+)")
vd_format_num_pattern = re.compile(r"vd-format num\s*:\s*(\d+)")
# vd_format_num_pattern = re.compile(r"vd-multi-inv num\s*:\s*(\d+)")
vd_multi_inv_num_pattern = re.compile(r"vd-multi-inv num\s*:\s*(\d+)")

compute_threshold = False

def draw(times, FCqueue, VDqueue, type):
    # Convert seconds to hours for x-axis
    times_avg_hours = [t / 3600 for t in times]

    # Plot
    plt.figure(figsize=(8, 6))  # Set the figure size

    # Plot each line
    plt.plot(times_avg_hours, FCqueue, marker='o', label='DF Queue Avg', linewidth=2)
    plt.plot(times_avg_hours, VDqueue, marker='^', label='VD Queue Avg', linewidth=2)

    # Add labels and title
    plt.xlabel("Time (Hours)", fontsize=12)  # Update x-axis label
    plt.ylabel("Queue Length", fontsize=12)
    plt.title("Average Queue Length Over Time", fontsize=14)

    # Add grid, legend, and customize
    plt.grid(True, linestyle='--', alpha=0.7)
    plt.legend(fontsize=10)
    plt.tight_layout()  # Adjust layout for better fit

    # Save and show
    plt.savefig("coverage" + "_" + type + ".pdf")  # Save as a file (optional)
    plt.show()  # Display the plot

def extract(log_file):
    times = []
    FCqueue = []
    VDqueue = []

    # log_file = "output-short"

    with open(log_file, "r") as f:
        current_time = None
        fc = None
        fc_mod = None

        for line in f:
            # Check for run time line
            time_match = time_pattern.search(line)
            if time_match:
                # If we have a complete set from before, record it
                if current_time is not None and fc is not None and fc_mod is not None:
                    times.append(current_time)
                    FCqueue.append(fc)
                    VDqueue.append(fc_mod)

                current_time = int(time_match.group(1))
                fc = None
                fc_mod = None
                continue

            # Check for queue lines
            if useQueueSize:
                qmatch = queue_pattern.search(line)
                if qmatch:
                    qtype = qmatch.group(1)
                    qsize = int(qmatch.group(2))
                    if qtype == "FC|":
                        fc = qsize
                    elif qtype == "FC_MOD|":
                        fc_mod = qsize
            else:
                format_match = format_num_pattern.search(line)
                vd_format_match = vd_format_num_pattern.search(line)
                vd_multi_inv_num_match = vd_multi_inv_num_pattern.search(line)
                if format_match:
                    fc = int(format_match.group(1))
                if vd_format_match:
                    fc_mod = int(vd_format_match.group(1))
                if countMultiVD:
                    if vd_multi_inv_num_match:
                        multi_inv_mod = int(vd_multi_inv_num_match.group(1)) # Count into total
                        fc_mod += multi_inv_mod
                if onlyCountMultiVD:
                    if vd_multi_inv_num_match:
                        multi_inv_mod = int(vd_multi_inv_num_match.group(1)) # Count into total
                        fc_mod = multi_inv_mod

        # After the loop, if we have a final set, append it
        if current_time is not None and fc is not None and fc_mod is not None:
            times.append(current_time)
            FCqueue.append(fc)
            VDqueue.append(fc_mod)
    return times, FCqueue, VDqueue

def interpolate(times_avg, times, queue):
    interp = interp1d(times, queue, kind='linear', fill_value="extrapolate")
    return interp(times_avg)

def compute_avg(dir, type):
    """_summary_
    In FC: we need to do a deduction for the results
    """
    # assert: type must be fc or bc or vd
    assert type in ["fc", "bc", "vd"]
    
    log_file0 = os.path.join(dir, type + "_output0")
    log_file1 = os.path.join(dir, type + "_output1")
    log_file2 = os.path.join(dir, type + "_output2")

    times0, FCqueue0, VDqueue0 = extract(log_file0)    
    times1, FCqueue1, VDqueue1 = extract(log_file1)
    times2, FCqueue2, VDqueue2 = extract(log_file2)
    
    compute_total_fc = True
    if (useQueueSize):
        if (compute_total_fc):
            if (type == "bc" or type == "vd"):
                FCqueue0 = [fc + fc_mod for fc, fc_mod in zip(FCqueue0, VDqueue0)]
                FCqueue1 = [fc + fc_mod for fc, fc_mod in zip(FCqueue1, VDqueue1)]
                FCqueue2 = [fc + fc_mod for fc, fc_mod in zip(FCqueue2, VDqueue2)]
        else:
            if (type == "fc"):
                FCqueue0 = [fc - fc_mod for fc, fc_mod in zip(FCqueue0, VDqueue0)]
                FCqueue1 = [fc - fc_mod for fc, fc_mod in zip(FCqueue1, VDqueue1)]
                FCqueue2 = [fc - fc_mod for fc, fc_mod in zip(FCqueue2, VDqueue2)]

    # Define the common time range
    times_avg = np.linspace(
        min(times0 + times1 + times2), max(times0 + times1 + times2), 100
    )

    # Interpolate for FCqueue
    FCqueue0_interp = interpolate(times_avg, times0, FCqueue0)
    FCqueue1_interp = interpolate(times_avg, times1, FCqueue1)
    FCqueue2_interp = interpolate(times_avg, times2, FCqueue2)
    # Compute the average
    FCqueue_avg = (FCqueue0_interp + FCqueue1_interp + FCqueue2_interp) / 3

    # Interpolate for VDqueue
    VDqueue0_interp = interpolate(times_avg, times0, VDqueue0)
    VDqueue1_interp = interpolate(times_avg, times1, VDqueue1)
    VDqueue2_interp = interpolate(times_avg, times2, VDqueue2)
    # Compute the average
    VDqueue_avg = (VDqueue0_interp + VDqueue1_interp + VDqueue2_interp) / 3

    # Remove all points where times_avg > 24 hours (24 * 3600 seconds)
    max_time = 24 * 3600 # 24h
    # max_time = 4 * 24 * 3600 # 4 days = 96 hours
    filtered_indices = [i for i, t in enumerate(times_avg) if t <= max_time]

    times_avg = [times_avg[i] for i in filtered_indices]
    FCqueue_avg = [FCqueue_avg[i] for i in filtered_indices]
    VDqueue_avg = [VDqueue_avg[i] for i in filtered_indices]

    return times_avg, FCqueue_avg, VDqueue_avg

def find_smallest_index_exceeding_threshold(queue, threshold):
    return next((i for i, q in enumerate(queue) if q > threshold), None)

def get_margin(system):
    y_margin = 0
    if (system == "cassandra"):
        y_margin = 25 
    elif (system == "hbase"):
        y_margin = 80
    elif (system == "hdfs"):
        y_margin = 20
    if onlyCountMultiVD:
        if (system == "cassandra"):
            y_margin = 3
        elif (system == "hbase"):
            y_margin = 1
        elif (system == "hdfs"):
            y_margin = 1
    return y_margin

def draw_comparison(time_bc, queue_bc, time_fc, queue_fc, time_vd, queue_vd, type, system, axs, i):
    sub_plt = axs[i]

    # Start plotting from 10 minutes (600 seconds)
    start_time = 600  # 10 minutes in seconds
    if (start_from_zero):
        start_time = 0
    
    # Filter the data from start_time and exclude negative values
    filtered_bc = [(t, q) for t, q in zip(time_bc, queue_bc) if t >= start_time and q >= 0]
    filtered_fc = [(t, q) for t, q in zip(time_fc, queue_fc) if t >= start_time and q >= 0]
    filtered_vd = [(t, q) for t, q in zip(time_vd, queue_vd) if t >= start_time and q >= 0]

    # Unpack filtered data
    time_bc_filtered, queue_bc_filtered = zip(*filtered_bc)
    time_fc_filtered, queue_fc_filtered = zip(*filtered_fc)
    time_vd_filtered, queue_vd_filtered = zip(*filtered_vd)
    
    # Ensure all plots start from (0, 0)
    time_bc_filtered = [0] + list(time_bc_filtered)
    queue_bc_filtered = [0] + list(queue_bc_filtered)

    time_fc_filtered = [0] + list(time_fc_filtered)
    queue_fc_filtered = [0] + list(queue_fc_filtered)

    time_vd_filtered = [0] + list(time_vd_filtered)
    queue_vd_filtered = [0] + list(queue_vd_filtered)

    # Convert seconds to hours for x-axis
    time_bc_hours = [t / 3600 for t in time_bc_filtered]
    time_fc_hours = [t / 3600 for t in time_fc_filtered]
    time_vd_hours = [t / 3600 for t in time_vd_filtered]

    # print the last value
    print(f"Last value for bc {system} {type} is {queue_bc_filtered[-1]}")
    print(f"Last value for fc {system} {type} is {queue_fc_filtered[-1]}")
    print(f"Last value for vd {system} {type} is {queue_vd_filtered[-1]}")

    # Plot lines with different line styles
    sub_plt.plot(time_vd_hours, queue_vd_filtered, linestyle='-', label='DF+VD+S', linewidth=my_line_width)  # Solid line
    sub_plt.plot(time_fc_hours, queue_fc_filtered, linestyle='--', label='DF+S', linewidth=my_line_width)  # Dashed line
    sub_plt.plot(time_bc_hours, queue_bc_filtered, linestyle=':', label='BC', linewidth=my_line_width)  # Dotted line
    
    sub_plt.set_xlabel("Time (Hours)", fontsize=x_my_font_size)

    y_name = "Distinct Violations"
    if i == 0:
        sub_plt.set_ylabel(y_name, fontsize=y_my_font_size)
    if draw_title:
        sub_plt.set_title(system, fontsize=title_my_font_size, weight='bold')  # System name at the top

    sub_plt.tick_params(axis='x', labelsize=x_my_font_size)
    sub_plt.tick_params(axis='y', labelsize=x_my_font_size)

    if (x_start_from_zero):
        sub_plt.set_xlim(left=0)  # ensures the x-axis starts from 0
    if (y_start_from_zero):
        sub_plt.set_ylim(bottom=0)  # sets the minimum y-value to 0

    # Add some space to the y-axis range
    sub_plt.set_ylim(bottom=-get_margin(system))

    sub_plt.yaxis.set_major_locator(ticker.MaxNLocator(4))  # Tries to set at most 4 ticks on the Y-axis
    sub_plt.xaxis.set_major_locator(ticker.MaxNLocator(4))  # Tries to set at most 4 ticks on the Y-axis

    sub_plt.grid(True, linestyle='--', alpha=0.7)
    if i == 2:
        plt.legend(fontsize=my_legend_font_size, loc='lower right')
    plt.tight_layout()  # Adjust layout for better fit

def run():
    system = None

    fig, axs = plt.subplots(1, 3, figsize=(18, 8))  # Adjust figsize as needed

    systems = ["cassandra", "hdfs", "hbase"]
    for i in range(3):
        system = systems[i]
        dir = system
        # if (system == "hdfs"):
        #      dir = "hdfs-eval1-without-decl"
        times_avg_fc, FCqueue_avg_fc, VDqueue_avg_fc = compute_avg(dir, "fc")
        times_avg_bc, FCqueue_avg_bc, VDqueue_avg_bc = compute_avg(dir, "bc")
        times_avg_vd, FCqueue_avg_vd, VDqueue_avg_vd = compute_avg(dir, "vd")

        # VD
        draw_comparison(times_avg_bc, VDqueue_avg_bc, times_avg_fc, VDqueue_avg_fc, times_avg_vd, VDqueue_avg_vd, "VD", system, axs, i)

    plt.savefig("all.pdf")  # Save as a file (optional)

    # FC
    # draw_comparison(times_avg_bc, FCqueue_avg_bc, times_avg_fc, FCqueue_avg_fc, times_avg_vd, FCqueue_avg_vd, "DF", system)

def draw_exp_model():
    # Parameters
    c = 0.8  # Initial probability
    k = -np.log(0.1 / 0.8) / 4  # Decay constant

    # Function to calculate probability
    def calculate_probability(N):
        return c * np.exp(-k * N)

    # Generate values for N
    N_values = np.arange(0, 11)  # Range of N from 0 to 10
    probabilities = calculate_probability(N_values)

    my_font_size = 18
    my_legend_font_size = 18

    # Plot the probabilities
    plt.figure(figsize=(8, 6))
    plt.plot(N_values, probabilities, marker='o', linestyle='-', label=f'c={c}, k={k:.2f}')
    plt.title('Exponential Probability Decay', fontsize=my_font_size)
    plt.xlabel('Distance', fontsize=my_font_size)
    plt.ylabel('Probability', fontsize=my_font_size)
    
    plt.xticks(fontsize=my_font_size)
    plt.yticks(fontsize=my_font_size)
    
    ax = plt.gca()
    ax.yaxis.set_major_locator(ticker.MaxNLocator(4))  # Tries to set at most 4 ticks on the Y-axis
    # ax.xaxis.set_major_locator(ticker.MaxNLocator(4))  # Tries to set at most 4 ticks on the Y-axis

    plt.grid(True)
    plt.legend(fontsize=my_legend_font_size)
    plt.savefig("exp.pdf")  # Save as a file (optional)

if __name__ == "__main__":
    run()
    # draw_exp_model()