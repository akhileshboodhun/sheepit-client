/*
 * Copyright (C) 2016 Laurent CLOUET
 * Author Laurent CLOUET <laurent.clouet@nopnop.net>
 *
 * This program is free software; you can redistribute it and/or 
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.sheepit.client.os.linux;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

import com.sheepit.client.hardware.cpu.CPU;

public class CPUx86 extends CPU {
	
	public void generateData() {
		try {
			String filePath = "/proc/cpuinfo";
			Scanner scanner = new Scanner(new File(filePath));
			
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				if (line.startsWith("model name")) {
					String buf[] = line.split(":");
					if (buf.length > 1) {
						setName(buf[1].trim());
					}
				}
				
				if (line.startsWith("cpu family")) {
					String buf[] = line.split(":");
					if (buf.length > 1) {
						setFamily(buf[1].trim());
					}
				}
				
				if (line.startsWith("model") && line.startsWith("model name") == false) {
					String buf[] = line.split(":");
					if (buf.length > 1) {
						setModel(buf[1].trim());
					}
				}
			}
			scanner.close();
		}
		catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
}